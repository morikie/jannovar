package de.charite.compbio.jannovar.annotation.builders;

import java.util.ArrayList;

import com.google.common.collect.ImmutableList;

import de.charite.compbio.jannovar.annotation.Annotation;
import de.charite.compbio.jannovar.annotation.InvalidGenomeChange;
import de.charite.compbio.jannovar.annotation.VariantEffect;
import de.charite.compbio.jannovar.impl.util.StringUtil;
import de.charite.compbio.jannovar.impl.util.Translator;
import de.charite.compbio.jannovar.reference.AminoAcidChange;
import de.charite.compbio.jannovar.reference.AminoAcidChangeNormalizer;
import de.charite.compbio.jannovar.reference.CDSPosition;
import de.charite.compbio.jannovar.reference.GenomeInterval;
import de.charite.compbio.jannovar.reference.GenomeVariant;
import de.charite.compbio.jannovar.reference.TranscriptModel;

/**
 * Builds {@link Annotation} objects for the deletion {@link GenomeVariant}s in
 * the given {@link TranscriptInfo}.
 *
 * @author Manuel Holtgrewe <manuel.holtgrewe@charite.de>
 */
public final class DeletionAnnotationBuilder extends AnnotationBuilder {

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
	DeletionAnnotationBuilder(TranscriptModel transcript, GenomeVariant change, AnnotationBuilderOptions options)
			throws InvalidGenomeChange {
		super(transcript, change, options);

		// Guard against invalid genome change.
		if (change.getRef().length() == 0 || change.getAlt().length() != 0)
			throw new InvalidGenomeChange("GenomeChange " + change + " does not describe a deletion.");
	}

	@Override
	public Annotation build() {
		// Go through top-level cases (clustered by how they are handled here) and build annotations for each of them
		// where applicable.

		if (!transcript.isCoding())
			return buildNonCodingAnnotation();

		final GenomeInterval changeInterval = change.getGenomeInterval();
		if (so.containsExon(changeInterval)) // deletion of whole exon
			return buildFeatureAblationAnnotation();
		else if (so.overlapsWithTranslationalStartSite(changeInterval))
			return buildStartLossAnnotation();
		else if (so.overlapsWithCDSExon(changeInterval) && so.overlapsWithCDS(changeInterval))
			return new CDSExonicAnnotationBuilder().build(); // can affect amino acids
		else if (so.overlapsWithCDSIntron(changeInterval) && so.overlapsWithCDS(changeInterval))
			return buildIntronicAnnotation(); // intron but no exon => intronic variant
		else if (so.overlapsWithFivePrimeUTR(changeInterval) || so.overlapsWithThreePrimeUTR(changeInterval))
			return buildUTRAnnotation();
		else if (so.overlapsWithUpstreamRegion(changeInterval) || so.overlapsWithDownstreamRegion(changeInterval))
			return buildUpOrDownstreamAnnotation();
		else
			return buildIntergenicAnnotation();
	}

	@Override
	protected String ncHGVS() {
		return StringUtil.concatenate(dnaAnno, "del");
	}

	private Annotation buildFeatureAblationAnnotation() {
		return new Annotation(transcript, change, ImmutableList.of(VariantEffect.TRANSCRIPT_ABLATION), locAnno,
				ncHGVS(), "p.0?");
	}

	private Annotation buildStartLossAnnotation() {
		return new Annotation(transcript, change, ImmutableList.of(VariantEffect.START_LOST), locAnno, ncHGVS(), "p.0?");
	}

	/**
	 * Helper class for generating annotations for exonic CDS variants.
	 *
	 * We use this helper class to simplify the access to the parameters such as
	 * {@link #wtCDSSeq} etc.
	 */
	private class CDSExonicAnnotationBuilder {
		final GenomeInterval changeInterval;

		final Translator t = Translator.getTranslator();

		final String wtCDSSeq;
		final String varCDSSeq;
		final int delFrameShift;

		final String wtAASeq;
		final String varAASeq;
		final int varAAStopPos;

		final CDSPosition changeBeginPos;
		final CDSPosition changeLastPos;

		// We keep the following three variables as state of the algorithm since we do not have easy-to-use triples in
		// Java.

		// the variant type, updated in handleFrameShiftCase() and handleNonFrameShiftCase()
		ArrayList<VariantEffect> varTypes = new ArrayList<VariantEffect>();
		// the amino acid change, updated in handleFrameShiftCase() and handleNonFrameShiftCase()
		AminoAcidChange aaChange;
		// the protein annotation, updated in handleFrameShiftCase() and handleNonFrameShiftCase()
		String protAnno;

		public CDSExonicAnnotationBuilder() {
			this.changeInterval = change.getGenomeInterval();
			this.wtCDSSeq = projector.getTranscriptStartingAtCDS();
			this.varCDSSeq = seqChangeHelper.getCDSWithGenomeVariant(change);
			this.delFrameShift = DeletionAnnotationBuilder.this.change.getRef().length() % 3;

			// Get the change begin position as CDS coordinate, handling introns and positions outside of CDS.
			this.changeBeginPos = projector.projectGenomeToCDSPosition(changeInterval.getGenomeBeginPos());
			this.changeLastPos = projector.projectGenomeToCDSPosition(changeInterval.getGenomeEndPos().shifted(-1));

			// Translate the variant CDS sequence and look for stop codon.
			this.wtAASeq = t.translateDNA(wtCDSSeq);
			this.varAASeq = t.translateDNA(varCDSSeq);
			this.varAAStopPos = varAASeq.indexOf('*', this.changeBeginPos.getPos() / 3);

			// protect against going behind transcript
			// "(...+2)/3" => round up integer division result
			final int wtAAEndPos = Math.min((changeLastPos.getPos() + 1 + 2) / 3, wtAASeq.length());
			final String delAA = wtAASeq.substring(changeBeginPos.getPos() / 3, wtAAEndPos);
			final int delta = (changeBeginPos.getFrameshift() == 0 ? 0 : 1);
			// protect against going behind transcript
			final int varAAEndPos = Math.min(changeBeginPos.getPos() / 3 + delta, varAASeq.length());
			final String insAA = varAASeq.substring(changeBeginPos.getPos() / 3, varAAEndPos);
			this.aaChange = new AminoAcidChange(changeBeginPos.getPos() / 3, delAA, insAA);
			this.aaChange = AminoAcidChangeNormalizer.truncateBothSides(this.aaChange);
			this.aaChange = AminoAcidChangeNormalizer.normalizeDeletion(wtAASeq, this.aaChange);
		}

		public Annotation build() {
			if (delFrameShift == 0)
				handleNonFrameShiftCase();
			else
				handleFrameShiftCase();

			return new Annotation(transcript, change, varTypes, locAnno, ncHGVS(), protAnno);
		}

		private void handleNonFrameShiftCase() {
			// The variant is a non-frameshift deletion. The deletion could span more than one exon and thus also affect
			// a splice donor or acceptor site, delete a stop codon. Further, the variant might also be a splice region
			// variant but that a lower priority than a non-frameshift deletion.
			//
			// Check for being a splice site variant. The splice donor, acceptor, and region intervals are disjoint.
			if (so.overlapsWithSpliceDonorSite(changeInterval))
				varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_DONOR_VARIANT));
			else if (so.overlapsWithSpliceAcceptorSite(changeInterval))
				varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_ACCEPTOR_VARIANT));
			else if (so.overlapsWithSpliceRegion(changeInterval))
				varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_REGION_VARIANT));
			// Check whether the variant overlaps with the stop site.
			if (so.overlapsWithTranslationalStopSite(changeInterval))
				varTypes.add(VariantEffect.STOP_LOST);

			// Differentiate the cases disruptive and a non-disruptive deletions.
			if (changeBeginPos.getPos() % 3 == 0)
				varTypes.add(VariantEffect.INFRAME_DELETION);
			else
				varTypes.add(VariantEffect.DISRUPTIVE_INFRAME_DELETION);

			// Differentiate between the cases where we have a stop codon and those where we don't.
			if (varAAStopPos >= 0) {
				String suffix = ""; // for "ins${aminoAcidCode}" in case of "delins" on AA level
				if (aaChange.getAlt().length() > 0)
					suffix = StringUtil.concatenate("ins", t.toLong(aaChange.getAlt()));

				char wtAAFirst = wtAASeq.charAt(aaChange.getPos());
				char wtAALast = wtAASeq.charAt(aaChange.getLastPos());
				if (aaChange.getPos() == aaChange.getLastPos())
					protAnno = StringUtil.concatenate("p.", t.toLong(wtAAFirst), aaChange.getPos() + 1, "del", suffix);
				else
					protAnno = StringUtil.concatenate("p.", t.toLong(wtAAFirst), aaChange.getPos() + 1, "_",
							t.toLong(wtAALast), aaChange.getLastPos() + 1, "del", suffix);
			} else {
				// There is no stop codon any more! Create a "probably no protein is produced".
				protAnno = "p.0?";
			}
		}

		private void handleFrameShiftCase() {
			// The variant is a frameshift deletion. The deletion could span more than one exon and thus also affect a
			// splice donor or acceptor site. Further, the variant might also be stop lost or splice region variant but
			// that has a lower priority than a frameshift deletion.
			//
			// Check for being a splice site variant. The splice donor, acceptor, and region intervals are disjoint.
			if (so.overlapsWithSpliceDonorSite(changeInterval))
				varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_DONOR_VARIANT));
			else if (so.overlapsWithSpliceAcceptorSite(changeInterval))
				varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_ACCEPTOR_VARIANT));
			else if (so.overlapsWithSpliceRegion(changeInterval))
				varTypes.addAll(ImmutableList.of(VariantEffect.SPLICE_REGION_VARIANT));
			// Check whether the variant overlaps with the stop site.
			if (so.overlapsWithTranslationalStopSite(changeInterval))
				varTypes.add(VariantEffect.STOP_LOST);

			// A nucleotide deletion can lead to an elongation of the transcript, annotate the specific case.
			if (varAASeq.length() > wtAASeq.length())
				varTypes.add(VariantEffect.FRAMESHIFT_ELONGATION);
			else if (varAASeq.length() < wtAASeq.length())
				varTypes.add(VariantEffect.FRAMESHIFT_TRUNCATION);
			else
				varTypes.add(VariantEffect.FRAMESHIFT_VARIANT);

			// Normalize the amino acid change, shifting to the right as long as change ref char equals var ref char.
			while (aaChange.getRef().length() > 0 && aaChange.getPos() < varAASeq.length()
					&& aaChange.getRef().charAt(0) == varAASeq.charAt(aaChange.getPos()))
				aaChange = aaChange.shiftRight();

			// Handle the case of deleting a stop codon at the very last entry of the translated amino acid string and
			// short-circuit.
			if (varTypes.contains(VariantEffect.STOP_LOST) && aaChange.getPos() == varAASeq.length()) {
				protAnno = StringUtil.concatenate("p.*", aaChange.getPos() + 1, "del?");
				return;
			}
			// Handle the case of deleting up to the end of the sequence.
			if (aaChange.getPos() >= varAASeq.length()) {
				if (aaChange.getRef().length() == 1)
					protAnno = StringUtil.concatenate("p.", t.toLong(wtAASeq.charAt(aaChange.getPos())),
							aaChange.getPos() + 1, "del");
				else
					protAnno = StringUtil.concatenate("p.", t.toLong(wtAASeq.charAt(aaChange.getPos())), aaChange.getPos() + 1,
							"_", t.toLong(wtAASeq.charAt(aaChange.getLastPos())), aaChange.getLastPos() + 1, "del");
				return;
			}

			char wtAA = wtAASeq.charAt(aaChange.getPos());
			char varAA = varAASeq.charAt(aaChange.getPos());
			int delta = (wtAA == '*') ? 0 : 1;

			// Compute suffix for HGVS protein annotation.
			String suffix = "*?"; // "?" or "*${distance}" in case of extension/frameshift
			if (varAAStopPos >= 0) {
				int stopCodonOffset = varAAStopPos - aaChange.getPos() + delta;
				suffix = StringUtil.concatenate("*", stopCodonOffset);
			}
			if (varTypes.contains(VariantEffect.STOP_LOST))
				protAnno = StringUtil.concatenate("p.*", aaChange.getPos() + 1, t.toLong(varAA), "ext", suffix);
			else
				protAnno = StringUtil
						.concatenate("p.", t.toLong(wtAA), aaChange.getPos() + 1, t.toLong(varAA), "fs", suffix);
		}
	}

}
