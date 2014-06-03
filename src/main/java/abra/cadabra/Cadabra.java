package abra.cadabra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.sf.samtools.Cigar;
import net.sf.samtools.CigarElement;
import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.TextCigarCodec;

public class Cadabra {

	private static final int MIN_SUPPORTING_READS = 2;
	
	private static final int MIN_DISTANCE_FROM_READ_END = 3;
	
	private ReadLocusReader normal;
	private ReadLocusReader tumor;

	public void callSomatic(String normal, String tumor) {
		this.normal = new ReadLocusReader(normal);
		this.tumor = new ReadLocusReader(tumor);
		
		process();
	}
	
	private void process() {
		Iterator<ReadsAtLocus> normalIter = normal.iterator();
		Iterator<ReadsAtLocus> tumorIter = tumor.iterator();
		
		ReadsAtLocus normalReads = null;
		ReadsAtLocus tumorReads = null;
		
		int count = 0;
		
		while (normalIter.hasNext() && tumorIter.hasNext()) {
			if (normalReads != null && tumorReads != null) {
				int compare = normalReads.compareLoci(tumorReads, normal.getSamHeader().getSequenceDictionary());
				
				if (compare < 0) {
					normalReads = normalIter.next();
				} else if (compare > 0) {
					tumorReads = tumorIter.next();
				} else {
					processLocus(normalReads, tumorReads);
					normalReads = normalIter.next();
					tumorReads = tumorIter.next();
				}
				
				if ((count % 1000000) == 0) {
					System.err.println("Position: " + normalReads.getChromosome() + ":" + normalReads.getPosition());
				}
				
				count += 1;
			} else {
				normalReads = normalIter.next();
				tumorReads = tumorIter.next();
			}
		}
	}
	
	private void processLocus(ReadsAtLocus normalReads, ReadsAtLocus tumorReads) {
		String chromosome = normalReads.getChromosome();
		int position = normalReads.getPosition();
		
		CigarElement tumorIndel = null;
		int tumorCount = 0;
		boolean hasSufficientDistanceFromReadEnd = false;
		
		for (SAMRecord read : tumorReads.getReads()) {
			IndelInfo readElement = checkForIndelAtLocus(read, position);
			if (tumorIndel == null && readElement != null) {
				tumorIndel = readElement.getCigarElement();
				tumorCount = 1;
			} else if (tumorIndel != null && readElement != null) {
				if (tumorIndel.equals(readElement.getCigarElement())) {
					// Increment tumor indel support count
					tumorCount += 1;
				} else {
					// We will not deal with multiple indels at a single locus for now.
					tumorIndel = null;
					tumorCount = 0;
					break;
				}
			}
			
			if (!hasSufficientDistanceFromReadEnd && tumorIndel != null && readElement != null && readElement.getCigarElement().equals(tumorIndel)) {
				hasSufficientDistanceFromReadEnd = sufficientDistanceFromReadEnd(read, readElement.getReadIndex());
			}
		}
		
		if (tumorCount >= MIN_SUPPORTING_READS && hasSufficientDistanceFromReadEnd) {
			for (SAMRecord read : normalReads.getReads()) {
				IndelInfo normalInfo = checkForIndelAtLocus(read.getAlignmentStart(), read.getCigar(), position);
				
				if (normalInfo != null && sufficientDistanceFromReadEnd(read, normalInfo.getReadIndex())) {
					// Don't allow call if any normal indel exists at this position.
					tumorIndel = null;
					tumorCount = 0;
					break;
				}
			}
		}
		
		if (tumorCount >= MIN_SUPPORTING_READS && hasSufficientDistanceFromReadEnd) {
			outputRecord(chromosome, position, normalReads, tumorReads, tumorIndel, tumorCount);
		}
	}
	
	private boolean sufficientDistanceFromReadEnd(SAMRecord read, int readIdx) {
		boolean ret = false;
		
		if (readIdx >= MIN_DISTANCE_FROM_READ_END &&
			readIdx <= read.getReadLength()-MIN_DISTANCE_FROM_READ_END-1) {
				ret = true;
		}
		
		return ret;
	}
	
	private void outputRecord(String chromosome, int position,
			ReadsAtLocus normalReads, ReadsAtLocus tumorReads, CigarElement indel,
			int tumorObs) {
		int normalDepth = normalReads.getReads().size();
		int tumorDepth = tumorReads.getReads().size();
		
		StringBuffer buf = new StringBuffer();
		buf.append(chromosome);
		buf.append('\t');
		buf.append(position);
		buf.append('\t');
		String type = "";
		if (indel.getOperator() == CigarOperator.D) {
			type = "D";
		} else if (indel.getOperator() == CigarOperator.I) {
			type = "I";
		}
		buf.append(type);
		buf.append('\t');
		buf.append(indel.getLength());
		buf.append('\t');
		buf.append(normalDepth);
		buf.append('\t');
		buf.append(tumorDepth);
		buf.append('\t');
		buf.append(tumorObs);
		
		System.out.println(buf.toString());
	}
	
	private IndelInfo checkForIndelAtLocus(SAMRecord read, int refPos) {

		IndelInfo elem = null;
		
		String contigInfo = read.getStringAttribute("YA");
		if (contigInfo != null) {
			// Get assembled contig info.
			String[] fields = contigInfo.split(":");
			int contigPos = Integer.parseInt(fields[1]);
			Cigar contigCigar = TextCigarCodec.getSingleton().decode(fields[2]);
			
			// Check to see if contig contains indel at current locus
			elem = checkForIndelAtLocus(contigPos, contigCigar, refPos);
			
			if (elem != null) {
				// Now check to see if this read supports the indel
				IndelInfo readElem = checkForIndelAtLocus(read.getAlignmentStart(),
						read.getCigar(), refPos);
				
				// Allow partially overlapping indels to support contig
				// (Should only matter for inserts)
				if (readElem == null || readElem.getCigarElement().getOperator() != elem.getCigarElement().getOperator()) {
					// Read element doesn't match contig indel
					elem = null;
				} else {
					elem.setReadIndex(readElem.getReadIndex());
				}
			}
		}
		
		return elem;
	}
	
	
	private IndelInfo checkForIndelAtLocus(int alignmentStart, Cigar cigar, int refPos) {
		
		IndelInfo ret = null;
		
		int readIdx = 0;
		int currRefPos = alignmentStart;
		for (CigarElement element : cigar.getCigarElements()) {
			if (element.getOperator() == CigarOperator.M) {
				readIdx += element.getLength();
				currRefPos += element.getLength();
			} else if (element.getOperator() == CigarOperator.I) {
				if (currRefPos == refPos+1) {
					ret = new IndelInfo(element, readIdx);
					break;
				}
				readIdx += element.getLength();
			} else if (element.getOperator() == CigarOperator.D) {
				if (currRefPos == refPos+1) {
					ret = new IndelInfo(element, readIdx);
					break;
				}				
				currRefPos += element.getLength();
			} else if (element.getOperator() == CigarOperator.S) {
				readIdx += element.getLength();
			}
		}
		
		return ret;
	}
	
	private char getReadBase(SAMRecord read, int index) {
		return (char) read.getReadBases()[index];
	}
	
	public static void main(String[] args) {
//		String normal = "/home/lmose/dev/abra/cadabra/normal_test2.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/tumor_test2.bam";
		
//		String normal = "/home/lmose/dev/abra/cadabra/normal.abra4.sort.bam";
//		String tumor = "/home/lmose/dev/abra/cadabra/tumor.abra4.sort.bam";

		String normal = "/home/lmose/dev/abra/cadabra/t2/ntest.bam";
		String tumor = "/home/lmose/dev/abra/cadabra/t2/ttest.bam";
		
//		String normal = args[0];
//		String tumor = args[1];
		
		new Cadabra().callSomatic(normal, tumor);
	}
}
