package exomizer.reference;


import  exomizer.exception.KGParseException;



/**
 * Encapsulates one line of the UCSC KnownGene.txt file. See {@link exomizer.io.UCSCKGParser} for an 
 * explanation of the structure of individual lines. Note that for now, we are not including
 * scaffolds such as chr4_ctg9_hap1 in the parsed lines (they throw an {@link exomizer.exception.KGParseException} and
 * are discarded by {@link exomizer.io.UCSCKGParser}). Consider implementing a more flexible parse in the future (TODO).
 * <P>
 * Some details about the implementation
 * <UL>
 * <LI>If we cannot find a cDNA sequence, we report "UNKNOWN" for mutations in this gene since it is impossible
 * to check the exact mutation position etc.
 * <LI>In annovar, $name
 * </UL>
 * @author Peter N Robinson
 * @version 0.01
 */
public class KnownGene implements java.io.Serializable, exomizer.common.Constants {
    /** Number of tab-separated fields in then UCSC knownGene.txt file (build hg19). */
    public static final int NFIELDS=12;
    /** Name of gene using UCSC knownGene id (for instance, uc011nca.2). For now, keep the
	version as part of the name. */
    private String kgID=null;
    /** Gene symbol of the known Gene. Can be null for some genes. Note that in annovar, $name2 corresponds to the
     * geneSymbol if available, otherwise the kgID is used.*/
    private String geneSymbol=null;
    /** Chromosome. chr1...chr22 are 1..22, chrX=23, chrY=24, mito=25. Ignore other chromosomes. 
     TODO. Add more flexible way of dealing with scaffolds etc.*/
    private byte chromosome;
    /** Strand of gene ('+' or '-' ) */
    private char strand;
    /** Transcription start position of gene. */
    private int txStart;
    /** Transcription end position of gene. */
    private int txEnd;
    /** CDS start position of gene. */
    private int cdsStart;
    /** CDS end position of gene. */
    private int cdsEnd;
    /** Position of start of CDS within the mRNA transcript. */
    private int rcdsStart;
    /** Number of exons of the gene */
    private byte exonCount;
    /** Start positions of each of the exons of this transcript */
    private int[] exonStarts=null;
    /** End positions of each of the exons of this transcript */
    private int[] exonEnds=null;
    /** Total length in nucleotides of mRNA */
    private int mRNAlength;
    /** Total length of coding sequence (CDS). Note that if CDS==0, then this is a non-coding gene. */
    private int CDSlength;
    /** cDNA sequence of the spliced RNA of this known gene transcript. */
    private String sequence=null;



    /**
     * The constructur parses a single line of the knownGene.txt file. </BR>
     * 	"uc021olp.1	chr1	-	38674705	38680439	38677458	38678111	4	38674705,38677405,38677769,38680388,	38676494,38677494,38678123,38680439,		uc021olp.1
     */
    public KnownGene(String line) throws KGParseException {
	String A[] = line.split("\t");
	if (A.length != NFIELDS) {
	    System.err.println("Malformed line in UCSC knownGene.txt file:\n" + line +
			       "\nExpected " + NFIELDS + " fields but there were " + A.length);
	    System.exit(1);
	}
	kgID = A[0]; // knownGene id
	try {
	    if (A[1].equals("chrX"))  this.chromosome = X_CHROMOSOME;     // 23
	    else if (A[1].equals("chrY")) this.chromosome = Y_CHROMOSOME; // 24
	    else if (A[1].equals("chrM")) this.chromosome = M_CHROMOSOME;  // 25
	    else {
		String tmp = A[1].substring(3); // remove leading "chr"
		this.chromosome = Byte.parseByte(tmp);
	    }
	} catch (NumberFormatException e) {  // scaffolds such as chrUn_gl000243 cause Exception to be thrown.
	    throw new KGParseException("Could not parse chromosome field: " + A[1]);
	}
	//System.out.println(A[0] + ":   " + A.length);
	this.strand = A[2].charAt(0);
	if (strand != '+' && strand != '-') {
	    throw new KGParseException("Malformed strand: " + A[2]);
	}
	try {
	    this.txStart = Integer.parseInt(A[3]) + 1; // +1 to convert to one-based fully closed numbering
	} catch (NumberFormatException e) {
	    throw new KGParseException("Could not parse txStart:" + A[3]);
	}
	try {
	    this.txEnd = Integer.parseInt(A[4]);
	} catch (NumberFormatException e) {
	    throw new KGParseException("Could not parse txEnd:" + A[4]);
	}
	try {
	    this.cdsStart = Integer.parseInt(A[5]) + 1;// +1 to convert to one-based fully closed numbering
	} catch (NumberFormatException e) {
	    throw new KGParseException("Could not parse cdsStart:" + A[5]);
	}
	try {
	    this.cdsEnd = Integer.parseInt(A[6]);
	} catch (NumberFormatException e) {
	    throw new KGParseException("Could not parse cdsEnd:" + A[6]);
	}
	try {
	    this.exonCount = Byte.parseByte(A[7]);
	}catch (NumberFormatException e) {
	    throw new KGParseException("Could not parse exonCount:" + A[7]);
	}
	parseExonStartsAndEnds(A[8],A[9]);
	calculateMRNALength();
	calculateCDSLength();
	calculateRefCDSStart();
    }


    /**
     * Calculate the total length of the mRNA from the exonEnd/exonStart data.
     * Note that if an exonEnd is 20 and the exon start is 10 then we have a total of 11 nucleotides,
     * thus the calculation is end-start+1 for each exon.
     */
    private void calculateMRNALength() {
		this.mRNAlength = 0;
		for (int i=0;i<this.exonCount;++i) {
			mRNAlength += this.exonEnds[i] - this.exonStarts[i] + 1;
		}
    }
    
	/**
	 * Calculate the position of the CDS start (i.e., the start codon) within the entire transcript,
	 * essentially equal to the length of the 5' UTR plus one. If the 5' UTR contains
	 * one or more introns, then we compensate for this by the cumlenintron calculation
	 * (see the code).
	 */
    private void calculateRefCDSStart() {
		int cumlenintron = 0; // cumulative length of introns at a given exon
		this.rcdsStart=0; // start of CDS within reference RNA sequence.
		if (this.isPlusStrand()) {
		    for (int k=0; k< this.exonCount;++k) {
				if (k>0)
					cumlenintron += this.getLengthOfIntron(k);
				if (this.cdsStart >= this.getExonStart(k)) {
					/* Calculate CDS start within mRNA sequence accurately
					by taking intron length into account. */
					this.rcdsStart = this.cdsStart - this.txStart - cumlenintron + 1;
					break;
				}
			}
		} else { /* i.e., minus strand */
			for (int k = this.exonCount-1; k>=0; k--) {
				if (k < this.exonCount-1) {
					cumlenintron += this.getLengthOfIntron(k);//($exonstart[$k+1]-$exonend[$k]-1);
				}
							
				if (this.cdsEnd <= this.getExonEnd(k)) {		
					//calculate CDS start accurately by considering intron length
					this.rcdsStart = this.txEnd-this.cdsEnd-cumlenintron+1;
					break;			
				}
			}
		}
	}
    

    /**
     * 	Calculates the length of the coding sequence based on the exon starts/stop. 
     * The logic is as follows
     * <OL>
     * <LI> If the cdsStart lies within [exonStarts[i]..exonEnd[i]] for exon i then the coding sequence begins in this exon
     * <LI> If additionally, the cdsEnd lies in the same exon, then the gene has only one exon, and we calculate the CDS length as 
     *	 cdsEnd - cdsStart + 1. Otherwise, this is the first of multiple exons, and we add the length of the CDS of the first
     *	 coding exon (exon i) as this.exonEnds[i] - cdsStart + 1. Note that this if clause is the first one that will increment
     * 	 the variable CDSlength, so that the other clauses can only be true once we have already seen the first coding exon.
     *  <LI>If cdsEnd is less than exonEnds[i], then we are in the last coding exon, and we need to add the length of the coding
     *	 segment, which is cdsEnd - exonStarts[i] + 1. We can then skip any remaining exons (break).
     *  <LI>If we have already seen the first coding exon, and cdsEnd is larger than exonEnds[i], then it is an internal exon and 
     *   we can add its entire length as exonEnds[i] - exonStarts[i] + 1.
     *  </OL>
     */
    private void calculateCDSLength() {
	this.CDSlength = 0;
	for (int i=0;i<this.exonCount;++i) {
	    if (this.cdsStart >= this.exonStarts[i] && this.cdsStart <= exonEnds[i]) { 
		if (this.cdsEnd <= exonEnds[i]) {
		    this.CDSlength = cdsEnd - cdsStart + 1; /* one-exon gene */
		} else {
		    this.CDSlength += this.exonEnds[i] - cdsStart + 1; /* currently in first or last CDS exon of multiexon gene */
		    continue; // go to next exon.
		}
	    } 
	    if ( CDSlength > 0 && cdsEnd < exonStarts[i])  {
		System.err.println("Impossible parsing scenario for " + this.kgID + " (CDSend is less than exon start)");
		System.exit(1);
	    } else if (CDSlength > 0 && this.cdsEnd <= exonEnds[i]) {
		CDSlength += cdsEnd - exonStarts[i] + 1; /* currently in last(+) or first(-) exon of multiexon gene */
		break;
	    } else if (CDSlength > 0 && this.cdsEnd > exonEnds[i]) {
		CDSlength += exonEnds[i] - exonStarts[i] + 1; /* currently in middle exon */
	    }
	}
    }

    /**
     * If this gene is not coding, then cdsEnd is one more than cdsStart.
     * @return true if this is a coding gene.
     */
    public boolean isCodingGene() {
	return (this.cdsStart != cdsEnd +1);
    }

    /** @return the transcription start of this gene (for genes on + strand) or the transcription end (for genes on - strand). */
    public int getTXStart() { return  this.txStart; }
    public int getTXEnd() { return this.txEnd; }
    public int getCDSStart() { return this.cdsStart; }
    public int getCDSEnd() { return this.cdsEnd; }
    public int getMRNALength() { return this.mRNAlength; }
    public int getCDSLength() { return this.CDSlength;}
    /** Return length of the actual cDNA sequence (rather than the length calculated from the exon positions,
     * which should however be the same. Can use for sanity checking. */
    public int getActualSequenceLength() { return this.sequence.length(); }
    public int getExonCount() { return this.exonCount; }
    public byte getChromosome() { return this.chromosome; }
	/** Return position of CDS (start codon) in entire mRNA transcript. */
    public int getRefCDSStart() { return this.rcdsStart;}
    /** @return The UCSC Gene ID, e.g., uc021olp.1. */
    public String getKnownGeneID() { return this.kgID; }
    /** @return '+' for Watson strand and '-' for Crick strand. */
    public char getStrand() { return this.strand; }
    /** @return true if strand is '+' */
    public boolean isPlusStrand() { return this.strand == '+'; }
    /** @return true if strand is '-' */
    public boolean isMinusStrand() { return this.strand == '-'; }
    /** Return the ucsc kg id, this corresponds to $name in annovar
     * @return name of this transcript, a UCSC knownGene id. */
     public String getName() { return this.kgID; }
     /** Return the gene symbol, corresponds to $name2 in annovar
      * @return genesymbol of this known gene transcript (if available, otherwise the kgID). */
      public String getName2() { 
			if (this.geneSymbol != null) 
				return this.geneSymbol;
			else
				return this.kgID;
       }
    
    
    /** This function is valid for exonic variants. It extracts the 
     * three nucleotides from the reference sequence that contain the
     * first nucleotide of the position of the variant
     * <P>
     * In annovar: $wtnt3 = substr ($refseqhash->{$seqid}, $refvarstart-$fs-1, 3);
     * @param refvarstart Position of first nucleotide of variant in cDNA sequence
     * @param frame_s The frame of the first nucleotide of the variant {0,1,2}
     */
    public String getWTCodonNucleotides(int refvarstart, int frame_s){
		int start = refvarstart - frame_s - 1;
		/* Substract one to get back to zero-based numbering.
		 * Subtract frame_s (i.e., 0,1,2) to get to start of codon in frame.
		 */
		 return this.sequence.substring(start, start+3); /* for + strand */
	}
	
	/** This function is valid for exonic variants. It extracts the 
     * three nucleotides from the reference sequence that are directly
     * 3' to the codon that contains the
     * first nucleotide of the position of the variant. If that was the 
     * last codon, the return ""; the empty string.
     * <P>
     * In annovar: $wtnt3 = substr ($refseqhash->{$seqid}, $refvarstart-$fs-1, 3);
     * √if (length ($refseqhash->{$seqid}) >= $refvarstart-$fs+3) {	#going into UTR
				$wtnt3_after = substr ($refseqhash->{$seqid}, $refvarstart-$fs+2, 3);
			} else {
				$wtnt3_after = '';					#last amino acid in the sequence without UTR (extremely rare situation) (example: 17        53588444        53588444        -       T       414     hetero)
			}
     * @param refvarstart Position of first nucleotide of variant in cDNA sequence
     * @param frame_s The frame of the first nucleotide of the variant {0,1,2}
     */
    public String getWTCodonNucleotidesAfterVariant(int refvarstart, int frame_s){
		if (getActualSequenceLength() >= refvarstart - frame_s + 3) {
			/* i.e., there is at least one codon 3' to codon in which variant begins */
			int start = refvarstart - frame_s + 2;
			/* Note add only 2 to convert back to zero-based numbering! */
			return this.sequence.substring(start,start+3);
		} else {
			return "";
		}
		/* NOTE ABOVE IS FOR + STRAND TODO */
		
	}
    
    /**
     * Calculates the length of the k'th intron, where k is a zero-based number.
     * Note that intron 1 begins after exon 1, so there are n-1 introns in a gene
     * with n exons.
     * <P>
     * Note that because exonEnds and exonStarts are both one-based, we return start[k]-end[k-1] -1 as the total length.
     * @param k number of intron (zero-based) whose length is to be sought
     * @return length of the k<superscript>th</superscript> intron (returns zero if k is 0)
     */
    public int getLengthOfIntron(int k){
	if (k==0) return 0;
	if (k>=this.exonCount) return 0;
	return exonStarts[k] - exonEnds[k-1] - 1;
    }


    /**
     * Calculates the length of exon k. Note that we assume that k is a valid exon count (zero-based).
     *
     * The chromosomal positions themselves are one-based fully closed, so that the length of an
     * exon is end-start+1.
     * @param k number of exon whose length is to be calculated.
     * @return length of exon in nucleotides.
     * @see exomizer.reference.Chromosome Chromosome (this class makes use of this method)
     */

    public int getLengthOfExon(int k) {
	return exonEnds[k] - exonStarts[k] + 1;
    }

    /**
     * @param k number of exon (zero-based)
     * @return chromosomal position of exon start (i.e., of the 5' end)
     */
    public int getExonStart(int k) {
	return this.exonStarts[k];
    }

     /**
     * @param k number of exon (zero-based)
     * @return chromosomal position of exon end (i.e., of the 3' end)
     */
    public int getExonEnd(int k) {
	return this.exonEnds[k];
    }

    /**
     * @param seq cDNA sequence of this knownGene transcript. 
     */
    public void setSequence(String seq) {
	this.sequence = seq;
    }




    /**
     * Parse the start and end of the exons. Note that in the UCSC database, positions are represented using
     * half-open, zero-based coordinates. That is, if start is 2 and end is 7, then the first nucleotide is at
     * position 3 (one-based) and the last nucleotide is at positon 7 (one-based). For now, we are switching
     * the coordinates to fully-closed one based by incrementing all start positions by one. This is the way
     * coordinates are typically represented in VCF files and is the way coordinate calculations are done
     * in annovar. At a later date, it may be worthwhile to switch to the UCSC-way of half-open zero based coordinates.
     * @param starts A string such as 14361,14969,15795,16606 representing start positions of exons on chromosome
     * @param ends A string such as 14829,15038,15947 representing end positions of exons on chromosome
     */
    private void parseExonStartsAndEnds(String starts, String ends) {
	this.exonStarts = new int[this.exonCount];
	this.exonEnds   = new int[this.exonCount];
	String A[] = starts.split(",");
	if (A.length != this.exonCount) {
	    System.err.println("Malformed exonStarts list: " + starts + " \n\tI expected " + exonCount + " exons");
	    System.err.println("This should never happen, consider if the knownGene.txt file is corrupted");
	    System.exit(1); 
	}
	for (int i=0;i<this.exonCount;++i) {
	    try {
		this.exonStarts[i] = Integer.parseInt(A[i]) + 1; // Change 0-based to 1-based numbering
	    } catch (NumberFormatException e) {
		System.err.println("Malformed exon start at position " + i + " of line " + starts);
		System.err.println("This should never happen, consider if the knownGene.txt file is corrupted");
		System.exit(1); 
	    }
	}
	// Now do the ends.
	A = ends.split(",");
	for (int i=0;i<this.exonCount;++i) {
	    try {
		this.exonEnds[i] = Integer.parseInt(A[i]);
	    } catch (NumberFormatException e) {
		System.err.println("Malformed exon end at position " + i + " of line " + starts);
		System.err.println("This should never happen, consider if the knownGene.txt file is corrupted");
		System.exit(1); 
	    }
	}
	

    }




}
