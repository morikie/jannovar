package de.charite.compbio.jannovar.hgvs.parser;

import org.junit.Assert;
import org.junit.Test;

import de.charite.compbio.jannovar.hgvs.parser.HGVSParser.Nt_change_ssrContext;

/**
 * Parser for HGVS deletion amino acid changes.
 *
 * @author Manuel Holtgrewe <manuel.holtgrewe@bihealth.de>
 */
public class HGVSParserNucleotideShortSequenceRepeatVariabilityTest extends HGVSParserTestBase {

	@Test
	public void testLengthOne() {
		HGVSParser parser = buildParserForString("123(3_4)", HGVSLexer.NUCLEOTIDE_CHANGE, false);
		Nt_change_ssrContext nt_change_ssr = parser.nt_change_ssr();
		Assert.assertEquals("(nt_change_ssr (nt_point_location (nt_base_location (nt_number 123))) ( 3 _ 4 ))",
				nt_change_ssr.toStringTree(parser));
	}

	@Test
	public void testLengthTwo() {
		HGVSParser parser = buildParserForString("123_124(3_4)", HGVSLexer.NUCLEOTIDE_CHANGE, false);
		Nt_change_ssrContext nt_change_ssr = parser.nt_change_ssr();
		Assert.assertEquals(
				"(nt_change_ssr (nt_range (nt_point_location (nt_base_location (nt_number 123))) _ (nt_point_location (nt_base_location (nt_number 124)))) ( 3 _ 4 ))",
				nt_change_ssr.toStringTree(parser));
	}

}
