package de.charite.compbio.jannovar.annotation.builders;

import java.util.ArrayList;

import com.google.common.collect.ImmutableList;

import de.charite.compbio.jannovar.annotation.Annotation;
import de.charite.compbio.jannovar.annotation.AnnotationMessage;
import de.charite.compbio.jannovar.annotation.InvalidGenomeChange;
import de.charite.compbio.jannovar.annotation.VariantEffect;
import de.charite.compbio.jannovar.impl.util.StringUtil;
import de.charite.compbio.jannovar.impl.util.Translator;
import de.charite.compbio.jannovar.reference.CDSPosition;
import de.charite.compbio.jannovar.reference.GenomeVariant;
import de.charite.compbio.jannovar.reference.GenomeInterval;
import de.charite.compbio.jannovar.reference.ProjectionException;
import de.charite.compbio.jannovar.reference.TranscriptModel;
import de.charite.compbio.jannovar.reference.TranscriptPosition;
import de.charite.compbio.jannovar.reference.TranscriptSequenceDecorator;

// TODO(holtgrew): Mutations near splice sites should be annotated as "p.?" as Mutalyzer does.

/**
 * Builds {@link Annotation} objects for the SNV {@link GenomeVariant}s in the given {@link TranscriptInfo}.
 *
 * @author Manuel Holtgrewe <manuel.holtgrewe@charite.de>
 */
public final class SNVAnnotationBuilder extends AnnotationBuilder {

	/**
	 * Override substitution annotation string in the case of coding change.
	 *
	 * For changes in coding regions, this is necessary since the transcript might not be the same as the reference
	 * (that the VCF file is generated from).
	 */
	private String hgvsSNVOverride = null;

	/**
	 * @param transcript
	 *            {@link TranscriptInfo} to build the annotation for
	 * @param change
	 *            {@link GenomeVariant} to build the annotation with
	 * @param options
	 *            the configuration to use for the {@link AnnotationBuilder}
	 * @throws InvalidGenomeChange
	 *             if <code>change</code> did not describe a deletion
	 */
	SNVAnnotationBuilder(TranscriptModel transcript, GenomeVariant change, AnnotationBuilderOptions options) throws InvalidGenomeChange {
		super(transcript, change, options);

		// guard against invalid genome change
		if (change.getRef().length() != 1 || change.getAlt().length() != 1)
			throw new InvalidGenomeChange("GenomeChange " + change + " does not describe a SNV.");
	}

	@Override
	public Annotation build() {
		// Go through top-level cases (clustered by how they are handled here) and build annotations for each of them
		// where applicable.

		if (!transcript.isCoding())
			return buildNonCodingAnnotation();

		final GenomeInterval changeInterval = change.getGenomeInterval();
		if (so.liesInCDSExon(changeInterval) && transcript.getCDSRegion().contains(changeInterval))
			return buildCDSExonicAnnotation(); // lies in coding part of exon
		else if (so.overlapsWithCDSIntron(changeInterval) && so.overlapsWithCDS(changeInterval))
			return buildIntronicAnnotation(); // intron but no exon => intronic variant
		else if (so.overlapsWithFivePrimeUTR(changeInterval) || so.overlapsWithThreePrimeUTR(changeInterval))
			return buildUTRAnnotation();
		else if (so.overlapsWithUpstreamRegion(changeInterval) || so.overlapsWithDownstreamRegion(changeInterval))
			return buildUpOrDownstreamAnnotation();
		else
			return buildIntergenicAnnotation();
	}

	private Annotation buildCDSExonicAnnotation() {
		// Get 0-based transcript and CDS positions.
		TranscriptPosition txPos;
		CDSPosition cdsPos;
		try {
			txPos = projector.genomeToTranscriptPos(change.getGenomePos());
			cdsPos = projector.genomeToCDSPos(change.getGenomePos());
		} catch (ProjectionException e) {
			throw new Error("Bug: CDS exon position must be translatable to transcript position");
		}

		// Check that the WT nucleotide from the transcript is consistent with change.ref and generate a warning message
		// if this is not the case.
		if (txPos.getPos() >= transcript.getSequence().length()
				|| !transcript.getSequence().substring(txPos.getPos(), txPos.getPos() + 1).equals(change.getRef()))
			messages.add(AnnotationMessage.WARNING_REF_DOES_NOT_MATCH_GENOME);

		// Compute the frame shift and codon start position.
		int frameShift = cdsPos.getPos() % 3;
		// Get the transcript codon. From this, we generate the WT and the variant codon. This is important in the case
		// where the transcript differs from the reference. This inconsistency of the reference and the transcript is
		// not necessarily an error in the data base but can also occur in the case of post-transcriptional changes of
		// the transcript.
		String transcriptCodon = seqDecorator.getCodonAt(txPos, cdsPos);
		String wtCodon = TranscriptSequenceDecorator.codonWithUpdatedBase(transcriptCodon, frameShift,
				change.getRef().charAt(0));
		String varCodon = TranscriptSequenceDecorator.codonWithUpdatedBase(transcriptCodon, frameShift,
				change.getAlt().charAt(0));

		// Construct the HGSV annotation parts for the transcript location and nucleotides (note that HGSV uses 1-based
		// positions).
		char wtNT = wtCodon.charAt(frameShift); // wild type nucleotide
		char varNT = varCodon.charAt(frameShift); // wild type amino acid
		hgvsSNVOverride = StringUtil.concatenate(wtNT, ">", varNT);

		// Construct annotation part for the protein.
		String wtAA = Translator.getTranslator().translateDNA3(wtCodon);
		String varAA = Translator.getTranslator().translateDNA3(varCodon);
		String protAnno = StringUtil.concatenate("p.", wtAA, cdsPos.getPos() / 3 + 1, varAA);
		if (wtAA.equals(varAA)) // simplify in the case of synonymous SNV
			protAnno = StringUtil.concatenate("p.=");

		// Compute variant type.
		ArrayList<VariantEffect> varTypes = computeVariantTypes(wtAA, varAA);
		GenomeInterval changeInterval = change.getGenomeInterval();
		if (so.overlapsWithTranslationalStartSite(changeInterval)) {
			varTypes.add(VariantEffect.START_LOST);
			protAnno = "p.0?";
		} else if (so.overlapsWithTranslationalStopSite(changeInterval)) {
			if (wtAA.equals(varAA)) { // change in stop codon, but no AA change
				varTypes.add(VariantEffect.STOP_RETAINED_VARIANT);
			} else { // change in stop codon, AA change
				varTypes.add(VariantEffect.STOP_LOST);
				String varNTString = seqChangeHelper.getCDSWithGenomeVariant(change);
				String varAAString = Translator.getTranslator().translateDNA(varNTString);
				int stopCodonPos = varAAString.indexOf('*', cdsPos.getPos() / 3);
				protAnno = StringUtil.concatenate(protAnno, "ext*", stopCodonPos - cdsPos.getPos() / 3);
			}
		}
		// Check for being a splice site variant. The splice donor, acceptor, and region intervals are disjoint.
		if (so.overlapsWithSpliceDonorSite(changeInterval))
			varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_DONOR_VARIANT));
		else if (so.overlapsWithSpliceAcceptorSite(changeInterval))
			varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_ACCEPTOR_VARIANT));
		else if (so.overlapsWithSpliceRegion(changeInterval))
			varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_REGION_VARIANT));

		// Build the resulting Annotation.
		return new Annotation(transcript, change, varTypes, locAnno, ncHGVS(), protAnno);
	}

	@Override
	protected String ncHGVS() {
		if (hgvsSNVOverride == null)
			return StringUtil.concatenate(dnaAnno, change.getRef(), ">", change.getAlt());
		else
			return StringUtil.concatenate(dnaAnno, hgvsSNVOverride);
	}

	/**
	 * @param wtAA
	 *            wild type amino acid
	 * @param varAA
	 *            variant amino acid
	 * @return variant types described by single nucleotide change
	 */
	private ArrayList<VariantEffect> computeVariantTypes(String wtAA, String varAA) {
		ArrayList<VariantEffect> result = new ArrayList<VariantEffect>();
		if (wtAA.equals(varAA))
			result.add(VariantEffect.SYNONYMOUS_VARIANT);
		else if (wtAA.equals("*"))
			result.add(VariantEffect.STOP_LOST);
		else if (varAA.equals("*"))
			result.add(VariantEffect.STOP_GAINED);
		else
			result.add(VariantEffect.MISSENSE_VARIANT);
		return result;
	}

}
