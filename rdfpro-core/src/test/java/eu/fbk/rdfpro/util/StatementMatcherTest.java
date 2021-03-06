package eu.fbk.rdfpro.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Assert;
import org.junit.Test;
import org.openrdf.model.Literal;
import org.openrdf.model.URI;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.OWL;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;

import eu.fbk.rdfpro.util.StatementMatcher;

public class StatementMatcherTest {

    @Test
    public void test() {

        final URI uri1 = new URIImpl("ex:uri1");
        final URI uri2 = new URIImpl("ex:uri2");
        final Literal lit1 = new LiteralImpl("label");

        final StatementMatcher matcher = StatementMatcher.builder()
                .addValues(null, RDF.TYPE, OWL.THING, null, null, "x")
                .addValues(null, RDFS.LABEL, null, null, null, "y", "w").build(null);

        Assert.assertFalse(matcher.match(uri1, OWL.SAMEAS, uri1, null));

        Assert.assertFalse(matcher.match(uri1, RDF.TYPE, RDFS.CLASS, uri2));
        Assert.assertEquals(ImmutableList.of(),
                matcher.map(uri1, RDF.TYPE, RDFS.CLASS, uri2, String.class));

        Assert.assertTrue(matcher.match(uri1, RDF.TYPE, OWL.THING, uri2));
        Assert.assertEquals(ImmutableList.of("x"),
                matcher.map(uri1, RDF.TYPE, OWL.THING, uri2, String.class));
        Assert.assertEquals(ImmutableList.of("x"),
                matcher.map(uri1, RDF.TYPE, OWL.THING, uri2, Object.class));
        Assert.assertEquals(ImmutableList.of(),
                matcher.map(uri1, RDF.TYPE, OWL.THING, uri2, Integer.class));

        Assert.assertTrue(matcher.match(uri1, RDFS.LABEL, lit1, uri2));
        Assert.assertEquals(ImmutableSet.of("y", "w"),
                ImmutableSet.copyOf(matcher.map(uri1, RDFS.LABEL, lit1, uri2, String.class)));
    }

    @Test
    public void test2() {

        final URI uri1 = new URIImpl("ex:uri1");
        final URI uri2 = new URIImpl("ex:uri2");
        final Literal lit1 = new LiteralImpl("label");

        final StatementMatcher matcher = StatementMatcher.builder()
                .addValues(null, null, null, null, null, "x")
                .addValues(null, RDFS.LABEL, null, null, null, "y", "w").build(null);

        Assert.assertTrue(matcher.match(uri1, RDF.TYPE, RDFS.CLASS, uri2));
        Assert.assertEquals(ImmutableList.of("x"),
                matcher.map(uri1, RDF.TYPE, RDFS.CLASS, uri2, String.class));

        Assert.assertTrue(matcher.match(uri1, RDFS.LABEL, lit1, uri2));
        Assert.assertEquals(ImmutableSet.of("x", "y", "w"),
                ImmutableSet.copyOf(matcher.map(uri1, RDFS.LABEL, lit1, uri2, String.class)));
    }

}
