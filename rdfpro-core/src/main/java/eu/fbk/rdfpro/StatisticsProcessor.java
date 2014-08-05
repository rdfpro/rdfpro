/*
 * RDFpro - An extensible tool for building stream-oriented RDF processing libraries.
 * 
 * Written in 2014 by Francesco Corcoglioniti <francesco.corcoglioniti@gmail.com> with support by
 * Marco Rospocher, Marco Amadori and Michele Mostarda.
 * 
 * To the extent possible under law, the author has dedicated all copyright and related and
 * neighboring rights to this software to the public domain worldwide. This software is
 * distributed without any warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package eu.fbk.rdfpro;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.vocabulary.OWL;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StatisticsProcessor extends RDFProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsProcessor.class);

    @Nullable
    private final String outputNamespace;

    @Nullable
    private final URI sourceProperty;

    @Nullable
    private final URI sourceContext;

    private final boolean processCooccurrences;

    StatisticsProcessor(@Nullable final String outputNamespace,
            @Nullable final URI sourceProperty, @Nullable final URI sourceContext,
            final boolean processCooccurrences) {
        this.outputNamespace = outputNamespace;
        this.sourceProperty = sourceProperty;
        this.sourceContext = sourceContext;
        this.processCooccurrences = processCooccurrences;
    }

    @Override
    public int getExtraPasses() {
        return 0;
    }

    @Override
    public RDFHandler getHandler(final RDFHandler handler) {
        return new Handler(handler);
    }

    private final class Handler implements RDFHandler, Closeable {

        private final RDFHandler handler;

        private final List<SourceStats> sourceList;

        private final Map<URI, SourceStats> sourceMap;

        private final ConcurrentHashMap<URI, URI> sourceInterner;

        private final List<TypeStats> typeList;

        private final Map<URI, TypeStats> typeMap;

        private final List<PropertyStats> propertyList;

        private final Map<URI, PropertyStats> propertyMap;

        private final List<Context> contextList;

        private final Map<Hash, Context> contextMap;

        private final Map<URI, TypeStats.Sampler> samplerMap;

        private final Set<String> mintedURIs;

        private Hash directBlockSubject;

        private final Map<SourceStats, PartialStats> directBlockStats;

        private final Set<PropertyStats.Partition> directBlockPartitions;

        private Hash inverseBlockObject;

        private long inverseBlockVersion;

        private final Tracker writeTracker;

        private final Tracker readTracker;

        private Sorter sorter;

        private boolean firstPass;

        Handler(final RDFHandler handler) {
            this.handler = handler;
            this.sourceList = new ArrayList<SourceStats>();
            this.sourceMap = new HashMap<URI, SourceStats>();
            this.sourceInterner = new ConcurrentHashMap<URI, URI>();
            this.typeList = new ArrayList<TypeStats>();
            this.typeMap = new HashMap<URI, TypeStats>();
            this.propertyList = new ArrayList<PropertyStats>();
            this.propertyMap = new HashMap<URI, PropertyStats>();
            this.contextList = new ArrayList<Context>();
            this.contextMap = new HashMap<Hash, Context>();
            this.samplerMap = new HashMap<URI, TypeStats.Sampler>();
            this.directBlockSubject = null;
            this.directBlockStats = new HashMap<SourceStats, PartialStats>();
            this.directBlockPartitions = new HashSet<PropertyStats.Partition>();
            this.inverseBlockObject = null;
            this.inverseBlockVersion = 0L;
            this.mintedURIs = new HashSet<String>();
            this.writeTracker = new Tracker(LOGGER, null, //
                    "%d records to sort (%d rec/s avg)", "4" + toString(), //
                    "%d records to sort (%d rec/s, %d rec/s avg)");
            this.readTracker = new Tracker(LOGGER, null, //
                    "%d records from sort (%d rec/s avg)", "5" + toString(), //
                    "%d records from sort (%d rec/s, %d rec/s avg)");
            this.sorter = null;
            this.firstPass = true;
        }

        @Override
        public void startRDF() throws RDFHandlerException {
            this.handler.startRDF();
            this.mintedURIs.clear();
            if (this.firstPass) {
                this.sorter = new Sorter(false) {

                    @Override
                    protected void decode(final Reader reader) throws Throwable {
                        Handler.this.readTracker.start();
                        while (true) {
                            final Record record = Record.read(reader);
                            if (record == null) {
                                break;
                            }
                            reader.read(); // consume delimiter 0
                            if (record.inverse) {
                                handleInverseRecord(record);
                            } else {
                                handleDirectRecord(record);
                            }
                            Handler.this.readTracker.increment();
                        }
                        Handler.this.readTracker.end();
                    }

                };
                try {
                    this.sorter.start();
                    this.writeTracker.start();
                } catch (final IOException ex) {
                    throw new RDFHandlerException(ex);
                }
            }
        }

        @Override
        public void handleComment(final String comment) throws RDFHandlerException {
            // ignore
        }

        @Override
        public void handleNamespace(final String prefix, final String uri)
                throws RDFHandlerException {
            // ignore
        }

        @Override
        public void handleStatement(final Statement statement) throws RDFHandlerException {

            if (!this.firstPass) {
                return;
            }

            final Resource s = statement.getSubject();
            final URI p = statement.getPredicate();
            final Value o = statement.getObject();
            final Resource c = statement.getContext();

            final boolean isURIType = o instanceof URI && p.equals(RDF.TYPE);
            final Hash sh = Hash.create(s);
            final Hash oh = isURIType ? null : Hash.create(o);

            PropertyStats ps;
            synchronized (this.propertyList) {
                ps = this.propertyMap.get(p);
                if (ps == null) {
                    ps = new PropertyStats(p, this.propertyList.size());
                    this.propertyMap.put(p, ps);
                    this.propertyList.add(ps);
                }
            }

            TypeStats ts = null;
            if (isURIType) {
                synchronized (this.typeList) {
                    ts = this.typeMap.get(o);
                    if (ts == null) {
                        ts = new TypeStats((URI) o, this.typeList.size());
                        this.typeMap.put((URI) o, ts);
                        this.typeList.add(ts);
                    }
                }
            }

            Context ctx = null;
            if (c != null) {
                final Hash ch = Hash.create(c);
                synchronized (this.contextList) {
                    ctx = this.contextMap.get(ch);
                    if (ctx == null) {
                        ctx = new Context(this.contextList.size());
                        this.contextMap.put(ch, ctx);
                        this.contextList.add(ctx);
                    }
                    ctx.used = true;
                }
            }

            if (o instanceof URI
                    && p.equals(StatisticsProcessor.this.sourceProperty)
                    && (StatisticsProcessor.this.sourceContext == null || Objects.equals(c,
                            StatisticsProcessor.this.sourceContext))) {
                URI source = this.sourceInterner.putIfAbsent((URI) o, (URI) o);
                source = source != null ? source : (URI) o;
                Context sctx;
                synchronized (this.contextList) {
                    sctx = this.contextMap.get(sh);
                    if (sctx == null) {
                        sctx = new Context(this.contextList.size());
                        this.contextMap.put(sh, sctx);
                        this.contextList.add(sctx);
                    }
                }
                synchronized (sctx) {
                    if (!Arrays.asList(sctx.sources).contains(source)) {
                        final URI[] array = new URI[sctx.sources.length + 1];
                        System.arraycopy(sctx.sources, 0, array, 0, sctx.sources.length);
                        array[array.length - 1] = source;
                        sctx.sources = array;
                    }
                }
            }

            final int pi = ps.index;
            final int ti = ts == null ? -1 : ts.index;
            final int ci = ctx == null ? -1 : ctx.index;

            final Record direct = Record.create(false, sh, pi, ti, oh, ci);
            final Record inverse = isURIType ? null : Record.create(true, null, pi, ti, oh, ci);

            try {
                final Writer writer = this.sorter.getWriter();
                direct.write(writer);
                writer.write(0);
                this.writeTracker.increment();
                if (inverse != null) {
                    inverse.write(writer);
                    writer.write(0);
                    this.writeTracker.increment();
                }
            } catch (final Throwable ex) {
                throw new RDFHandlerException(ex);
            }

            synchronized (ps) {
                if (ps.sampler == null) {
                    ps.sampler = new PropertyStats.Sampler();
                }
                ps.sampler.add(statement);
            }

            if (s instanceof URI) {
                synchronized (this.samplerMap) {
                    TypeStats.Sampler sampler = this.samplerMap.get(s);
                    if (sampler != null) {
                        sampler.add(statement);
                        if (ts != null && ts.sampler == null) {
                            ts.sampler = sampler;
                        }
                    } else if (ts != null && ts.sampler == null) {
                        sampler = new TypeStats.Sampler();
                        sampler.add(statement);
                        ts.sampler = sampler;
                        this.samplerMap.put((URI) s, sampler);
                    }
                }
            }
        }

        @Override
        public void endRDF() throws RDFHandlerException {
            if (this.firstPass) {
                try {
                    this.writeTracker.end();

                    this.typeMap.clear(); // no more used
                    this.propertyMap.clear(); // no more used
                    this.contextMap.clear(); // no more used
                    this.samplerMap.clear(); // no more used
                    this.sourceInterner.clear(); // no more used

                    final SourceStats s0 = new SourceStats(null, 0);
                    this.sourceMap.put(null, s0);
                    this.sourceList.add(s0);

                    for (int i = 0; i < this.contextList.size(); ++i) {
                        final Context ctx = this.contextList.get(i);
                        if (!ctx.used) {
                            this.contextList.set(i, null);
                        } else {
                            for (final URI source : ctx.sources) {
                                SourceStats ss = this.sourceMap.get(source);
                                if (ss == null) {
                                    ss = new SourceStats(source, this.sourceList.size());
                                    this.sourceMap.put(source, ss);
                                    this.sourceList.add(ss);
                                }
                            }
                        }
                    }

                    for (final TypeStats ts : this.typeList) {
                        ts.partitions = new TypeStats.Partition[this.sourceList.size()];
                        ts.partitions[0] = new TypeStats.Partition();
                        if (ts.sampler != null) {
                            ts.example = ts.sampler.build();
                            ts.sampler = null; // release memory
                        }
                    }

                    for (final PropertyStats ps : this.propertyList) {
                        ps.partitions = new PropertyStats.Partition[this.sourceList.size()];
                        ps.partitions[0] = new PropertyStats.Partition();
                        if (ps.sampler != null) {
                            ps.example = ps.sampler.build();
                            ps.sampler = null; // release memory
                        }
                    }

                    LOGGER.debug("Status: {} properties, {} types, {} contexts, " + "{} sources",
                            this.propertyList.size(), this.typeList.size(),
                            this.contextMap.size(), this.sourceList.size());

                    this.sorter.end();
                    this.sorter = null;
                    handleDirectRecord(null); // flush last direct block

                } catch (final Throwable ex) {
                    Util.propagateIfPossible(ex, RDFHandlerException.class);
                    throw new RDFHandlerException(ex);
                }
            }
            emitStatistics();
            this.handler.endRDF();
            this.firstPass = false;
        }

        @Override
        public void close() throws IOException {
            Util.closeQuietly(this.sorter);
            Util.closeQuietly(this.handler);
        }

        private void handleDirectRecord(@Nullable final Record record) {

            if (record == null || !record.subject.equals(this.directBlockSubject)) {
                for (final Map.Entry<SourceStats, PartialStats> e : this.directBlockStats
                        .entrySet()) {
                    final int index = e.getKey().index;
                    final PartialStats s = e.getValue();
                    if (s.tss != null) {
                        for (final TypeStats ts : s.tss) {
                            TypeStats.Partition tp = ts.partitions[index];
                            if (tp == null) {
                                tp = new TypeStats.Partition();
                                ts.partitions[index] = tp;
                            }
                            tp.triples += s.triples;
                            tp.tboxTriples += s.tboxTriples;
                            tp.aboxTriples += s.aboxTriples;
                            tp.typeTriples += s.typeTriples;
                            tp.sameAsTriples += s.sameAsTriples;
                            tp.predicates += s.pss == null ? 0 : s.pss.size();
                            tp.entities += s.entities;
                            if (StatisticsProcessor.this.processCooccurrences) {
                                tp.types = tp.types != null ? tp.types : new BitSet();
                                tp.properties = tp.properties != null ? tp.properties
                                        : new BitSet();
                                if (s.types != null) {
                                    tp.types.or(s.types);
                                }
                                if (s.properties != null) {
                                    tp.properties.or(s.properties);
                                }
                            }
                        }
                    }
                }
                this.directBlockStats.clear();
                this.directBlockPartitions.clear();
                if (record == null) {
                    return;
                }
                this.directBlockSubject = record.subject;
            }

            if (record.object != null) {
                final boolean isLiteral = record.object.isLiteral();
                final PropertyStats ps = this.propertyList.get(record.property);
                if (ps.detectedType == null) {
                    ps.detectedType = isLiteral ? OWL.DATATYPEPROPERTY : OWL.OBJECTPROPERTY;
                } else if (ps.detectedType == OWL.DATATYPEPROPERTY && !isLiteral
                        || ps.detectedType == OWL.OBJECTPROPERTY && isLiteral) {
                    ps.detectedType = RDF.PROPERTY;
                }
            }

            handleDirectRecordHelper(record, this.sourceList.get(0));
            if (record.context >= 0) {
                final Context ctx = this.contextList.get(record.context);
                for (final URI source : ctx.sources) {
                    handleDirectRecordHelper(record, this.sourceMap.get(source));
                }
            }
        }

        private void handleDirectRecordHelper(final Record record, final SourceStats ss) {

            final boolean isEntity = record.subject.isURI();

            PartialStats s = this.directBlockStats.get(ss);
            if (s == null) {
                s = new PartialStats();
                this.directBlockStats.put(ss, s);
                if (isEntity) {
                    ++ss.entities;
                    ++s.entities;
                }
                if (StatisticsProcessor.this.processCooccurrences) {
                    ss.types = ss.types != null ? ss.types : new BitSet();
                    ss.properties = ss.properties != null ? ss.properties : new BitSet();
                    s.types = new BitSet();
                    s.properties = new BitSet();
                }
            }

            ++ss.triples;
            ++s.triples;

            if (record.type >= 0) {
                final TypeStats ts = this.typeList.get(record.type);
                s.tss = s.tss != null ? s.tss : new HashSet<TypeStats>();
                s.tss.add(ts);
                if (Util.TBOX_CLASSES.contains(ts.type)) {
                    ++ss.tboxTriples;
                    ++s.tboxTriples;
                } else {
                    ++ss.aboxTriples;
                    ++s.aboxTriples;
                    ++ss.typeTriples;
                    ++s.typeTriples;
                }
                if (StatisticsProcessor.this.processCooccurrences) {
                    ss.types.set(ts.index);
                    s.types.set(ts.index);
                }

            } else {
                final PropertyStats ps = this.propertyList.get(record.property);
                s.pss = s.pss != null ? s.pss : new HashSet<PropertyStats>();
                s.pss.add(ps);
                PropertyStats.Partition pp = ps.partitions[ss.index];
                if (pp == null) {
                    pp = new PropertyStats.Partition();
                    ps.partitions[ss.index] = pp;
                }
                ++pp.triples;
                if (this.directBlockPartitions.add(pp)) {
                    ++pp.distinctSubjects;
                    pp.entities += isEntity ? 1 : 0;
                }
                if (Util.TBOX_PROPERTIES.contains(ps.property)) {
                    ++ss.tboxTriples;
                    ++s.tboxTriples;
                } else {
                    ++ss.aboxTriples;
                    ++s.aboxTriples;
                    if (ps.property.equals(OWL.SAMEAS)) {
                        ++ss.sameAsTriples;
                        ++s.sameAsTriples;
                    }
                }
                if (StatisticsProcessor.this.processCooccurrences) {
                    ss.properties.set(ps.index);
                    s.properties.set(ps.index);
                }
            }
        }

        private void handleInverseRecord(final Record record) {
            if (!record.object.equals(this.inverseBlockObject)) {
                ++this.inverseBlockVersion;
                this.inverseBlockObject = record.object;
            }
            final PropertyStats ps = this.propertyList.get(record.property);
            final PropertyStats.Partition p0 = ps.partitions[0];
            if (p0.version < this.inverseBlockVersion) {
                p0.version = this.inverseBlockVersion;
                ++p0.distinctObjects;
            }
            if (record.context >= 0) {
                final Context ctx = this.contextList.get(record.context);
                for (final URI source : ctx.sources) {
                    final SourceStats ss = this.sourceMap.get(source); // TODO avoid sourceMap
                    PropertyStats.Partition p = ps.partitions[ss.index];
                    if (p == null) {
                        p = new PropertyStats.Partition();
                        ps.partitions[ss.index] = p;
                    } else if (p.version == this.inverseBlockVersion) {
                        continue;
                    }
                    ++p.distinctObjects;
                }
            }
        }

        private void emitStatistics() throws RDFHandlerException {

            final ValueFactory vf = Util.FACTORY;

            this.handler.handleNamespace(VOID.PREFIX, VOID.NAMESPACE);
            this.handler.handleNamespace(VOIDX.PREFIX, VOIDX.NAMESPACE);

            final Map<URI, URI> spURIs = new HashMap<URI, URI>();
            for (final SourceStats s : this.sourceList) {
                final URI uri = mintURI(s.source != null ? s.source : VOID.DATASET);
                spURIs.put(s.source, uri);
                emit(uri, RDF.TYPE, VOID.DATASET);
                emit(uri, VOIDX.SOURCE, s.source);
                emit(uri, VOID.ENTITIES, vf.createLiteral(s.entities));
                emit(uri, VOID.TRIPLES, vf.createLiteral(s.triples));
                emit(uri, VOIDX.TBOX_TRIPLES, vf.createLiteral(s.tboxTriples));
                emit(uri, VOIDX.ABOX_TRIPLES, vf.createLiteral(s.aboxTriples));
                emit(uri, VOIDX.TYPE_TRIPLES, vf.createLiteral(s.typeTriples));
                emit(uri, VOIDX.SAME_AS_TRIPLES, vf.createLiteral(s.sameAsTriples));
                if (s.types != null) {
                    emit(uri, VOID.CLASSES, vf.createLiteral(s.types.cardinality()));
                }
                if (s.properties != null) {
                    emit(uri, VOID.PROPERTIES, vf.createLiteral(s.properties.cardinality()));
                }
            }

            for (final TypeStats ts : this.typeList) {
                final TypeStats.Partition p0 = ts.partitions[0];
                final String label = Util.formatValue(ts.type).replace("<", "").replace(">", "")
                        + " (" + p0.entities + ")";
                emit(ts.type, VOIDX.LABEL, vf.createLiteral(label));
                if (ts.example != null) {
                    emit(ts.type, VOIDX.EXAMPLE, vf.createLiteral(ts.example));
                }
                for (int i = 0; i < ts.partitions.length; ++i) {
                    final TypeStats.Partition p = ts.partitions[i];
                    if (p != null) {
                        final URI source = this.sourceList.get(i).source;
                        final URI spURI = spURIs.get(source);
                        final URI tpURI = mintURI(source, ts.type);
                        emit(spURI, VOID.CLASS_PARTITION, tpURI);
                        emit(tpURI, RDF.TYPE, VOID.DATASET);
                        emit(tpURI, VOIDX.SOURCE, source);
                        emit(tpURI, VOID.CLASS, ts.type);
                        emit(tpURI, VOID.ENTITIES, vf.createLiteral(p.entities));
                        emit(tpURI, VOID.TRIPLES, vf.createLiteral(p.triples));
                        emit(tpURI, VOIDX.TBOX_TRIPLES, vf.createLiteral(p.tboxTriples));
                        emit(tpURI, VOIDX.ABOX_TRIPLES, vf.createLiteral(p.aboxTriples));
                        emit(tpURI, VOIDX.TYPE_TRIPLES, vf.createLiteral(p.typeTriples));
                        emit(tpURI, VOIDX.SAME_AS_TRIPLES, vf.createLiteral(p.sameAsTriples));
                        if (p.types != null) {
                            emit(tpURI, VOID.CLASSES, vf.createLiteral(p.types.cardinality()));
                        }
                        if (p.properties != null) {
                            emit(tpURI, VOID.PROPERTIES,
                                    vf.createLiteral(p.properties.cardinality()));
                        }
                        if (p.entities > 0) {
                            emit(tpURI, VOIDX.AVERAGE_PROPERTIES,
                                    vf.createLiteral((double) p.predicates / p.entities));
                        }
                    }
                }
            }

            for (final PropertyStats ps : this.propertyList) {
                final boolean isTBox = Util.TBOX_PROPERTIES.contains(ps.property);
                final boolean isType = ps.property.equals(RDF.TYPE);
                final boolean isSameAs = ps.property.equals(OWL.SAMEAS);
                final PropertyStats.Partition p0 = ps.partitions[0];
                final boolean fun = p0.triples > 0 && p0.triples == p0.distinctSubjects;
                final boolean invfun = p0.triples > 0 && p0.triples == p0.distinctObjects;
                final boolean data = OWL.DATATYPEPROPERTY.equals(ps.detectedType);
                final boolean object = OWL.OBJECTPROPERTY.equals(ps.detectedType);
                final String label = String.format("%s (%d, %s%s%s)", Util
                        .formatValue(ps.property).replace("<", "").replace(">", ""), p0.triples,
                        data ? "D" : object ? "O" : "P", fun ? "F" : "", invfun ? "I" : "");
                emit(ps.property, VOIDX.LABEL, vf.createLiteral(label));
                emit(ps.property, VOIDX.TYPE, ps.detectedType);
                if (fun) {
                    emit(ps.property, VOIDX.TYPE, OWL.FUNCTIONALPROPERTY);
                }
                if (invfun) {
                    emit(ps.property, VOIDX.TYPE, OWL.INVERSEFUNCTIONALPROPERTY);
                }
                if (ps.example != null) {
                    emit(ps.property, VOIDX.EXAMPLE, vf.createLiteral(ps.example));
                }
                for (int i = 0; i < ps.partitions.length; ++i) {
                    final PropertyStats.Partition p = ps.partitions[i];
                    if (p != null) {
                        final URI source = this.sourceList.get(i).source;
                        final URI spURI = spURIs.get(source);
                        final URI ppURI = mintURI(source, ps.property);
                        emit(spURI, VOID.PROPERTY_PARTITION, ppURI);
                        emit(ppURI, RDF.TYPE, VOID.DATASET);
                        emit(ppURI, VOIDX.SOURCE, source);
                        emit(ppURI, VOID.PROPERTY, ps.property);
                        emit(ppURI, VOID.CLASSES, vf.createLiteral(0));
                        emit(ppURI, VOID.PROPERTIES, vf.createLiteral(1));
                        emit(ppURI, VOID.ENTITIES, vf.createLiteral(p.entities));
                        emit(ppURI, VOID.TRIPLES, vf.createLiteral(p.triples));
                        emit(ppURI, VOIDX.TBOX_TRIPLES, vf.createLiteral(isTBox ? p.triples : 0));
                        emit(ppURI, VOIDX.ABOX_TRIPLES, vf.createLiteral(isTBox ? 0 : p.triples));
                        emit(ppURI, VOIDX.TYPE_TRIPLES, vf.createLiteral(isType ? p.triples : 0));
                        emit(ppURI, VOIDX.SAME_AS_TRIPLES,
                                vf.createLiteral(isSameAs ? p.triples : 0));
                        emit(ppURI, VOID.DISTINCT_SUBJECTS, vf.createLiteral(p.distinctSubjects));
                        emit(ppURI, VOID.DISTINCT_OBJECTS, vf.createLiteral(p.distinctObjects));
                    }
                }
            }
        }

        private void emit(@Nullable final Resource subject, @Nullable final URI predicate,
                @Nullable final Value object) throws RDFHandlerException {
            if (subject != null && predicate != null && object != null) {
                this.handler.handleStatement(Util.FACTORY.createStatement(subject, predicate,
                        object));
            }
        }

        private URI mintURI(final URI... inputURIs) {
            final StringBuilder builder = new StringBuilder();
            if (StatisticsProcessor.this.outputNamespace != null) {
                builder.append(StatisticsProcessor.this.outputNamespace);
            } else {
                builder.append("stats:");
            }
            boolean started = false;
            for (final URI uri : inputURIs) {
                if (uri != null) {
                    if (started) {
                        builder.append("_");
                    }
                    started = true;
                    builder.append(uri.getLocalName());
                }
            }
            final String base = builder.toString();
            for (int i = 0; i < 1000; ++i) {
                final String candidate = i == 0 ? base : base + "_" + i;
                if (this.mintedURIs.add(candidate)) {
                    return Util.FACTORY.createURI(candidate);
                }
            }
            throw new Error();
        }

    }

    private static final class PartialStats {

        @Nullable
        Set<TypeStats> tss;

        @Nullable
        Set<PropertyStats> pss;

        @Nullable
        BitSet types;

        @Nullable
        BitSet properties;

        long entities;

        long triples;

        long tboxTriples;

        long aboxTriples;

        long typeTriples;

        long sameAsTriples;

    }

    private static final class SourceStats {

        @Nullable
        final URI source;

        final int index;

        @Nullable
        BitSet types;

        @Nullable
        BitSet properties;

        long entities;

        long triples;

        long tboxTriples;

        long aboxTriples;

        long typeTriples;

        long sameAsTriples;

        SourceStats(final URI source, final int index) {
            this.source = source;
            this.index = index;
            this.types = null;
            this.properties = null;
            this.entities = 0;
            this.triples = 0;
            this.tboxTriples = 0;
            this.aboxTriples = 0;
            this.typeTriples = 0;
            this.sameAsTriples = 0;
        }

    }

    private static final class TypeStats {

        @Nullable
        final URI type;

        final int index;

        @Nullable
        Sampler sampler;

        @Nullable
        String example;

        @Nullable
        Partition[] partitions;

        TypeStats(@Nullable final URI type, final int index) {
            this.type = type;
            this.index = index;
        }

        static final class Partition {

            BitSet types;

            BitSet properties;

            long entities;

            long triples;

            long tboxTriples;

            long aboxTriples;

            long typeTriples;

            long sameAsTriples;

            long predicates;

        }

        static class Sampler {

            private static final int MAX_VALUE_LENGTH = 40;

            private static final int MAX_STATEMENTS = 20;

            private URI id;

            private final List<Value> data;

            Sampler() {
                this.data = new ArrayList<Value>();
            }

            synchronized void add(final Statement statement) {
                if (this.data.size() < MAX_STATEMENTS * 2) {
                    this.id = (URI) statement.getSubject();
                    this.data.add(statement.getPredicate());
                    this.data.add(statement.getObject());
                }
            }

            synchronized String build() {
                final List<String> lines = new ArrayList<String>();
                for (int i = 0; i < this.data.size(); i += 2) {
                    final String predicate = Util.formatValue(this.data.get(i));
                    final String object = Util.formatValue(Util.shortenValue(this.data.get(i + 1),
                            MAX_VALUE_LENGTH));
                    lines.add(predicate + " " + object);
                }
                Collections.sort(lines);
                final StringBuilder builder = new StringBuilder(Util.formatValue(this.id));
                for (int i = 0; i < lines.size(); ++i) {
                    builder.append("\n    ").append(lines.get(i));
                    builder.append(i < lines.size() - 1 ? ';' : '.');
                }
                return builder.toString();
            }

        }

    }

    private static final class PropertyStats {

        @Nullable
        final URI property;

        final int index;

        @Nullable
        Sampler sampler;

        @Nullable
        String example;

        @Nullable
        URI detectedType;

        @Nullable
        Partition[] partitions;

        PropertyStats(final URI property, final int index) {
            this.property = property;
            this.index = index;
            this.detectedType = null;
        }

        static final class Partition {

            long entities;

            long triples;

            long distinctSubjects;

            long distinctObjects;

            long version;

        }

        static final class Sampler {

            private static final int MAX_VALUE_LENGTH = 40;

            private static final int MAX_STATEMENTS = 3;

            private final Statement[] statements;

            private boolean haveBNode;

            private boolean haveLiteral;

            private boolean haveURI;

            private int size;

            Sampler() {
                this.statements = new Statement[MAX_STATEMENTS];
                this.haveBNode = false;
                this.haveLiteral = false;
                this.haveURI = false;
                this.size = 0;
            }

            synchronized void add(final Statement statement) {

                final Resource s = statement.getSubject();
                final Value o = statement.getObject();
                final boolean isURI = o instanceof URI;
                final boolean isBNode = o instanceof BNode;
                final boolean isLiteral = o instanceof Literal;

                if (!(s instanceof URI)
                        || this.size == this.statements.length
                        && (isURI && this.haveURI || isBNode && this.haveBNode || isLiteral
                                && this.haveLiteral)) {
                    return;
                }

                int index = -1;
                for (int i = 0; i < this.statements.length; ++i) {
                    final Statement stmt = this.statements[i];
                    if (stmt == null) {
                        index = i;
                        ++this.size;
                        break;
                    } else if (stmt.equals(statement)) {
                        return;
                    } else if (!this.haveURI && isURI //
                            || !this.haveBNode && isBNode //
                            || !this.haveLiteral && isLiteral) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0) {
                    this.statements[index] = statement;
                    this.haveURI |= isURI;
                    this.haveBNode |= isBNode;
                    this.haveLiteral |= isLiteral;
                }
            }

            synchronized String build() {
                final StringBuilder builder = new StringBuilder();
                for (final Statement statement : this.statements) {
                    if (statement != null) {
                        builder.append("\n    ")
                                .append(Util.formatValue(statement.getSubject()))
                                .append(" ")
                                .append(Util.formatValue(statement.getPredicate()))
                                .append(" ")
                                .append(Util.formatValue(Util.shortenValue(statement.getObject(),
                                        MAX_VALUE_LENGTH))).append(" .");
                    }
                }
                return builder.toString();
            }

        }

    }

    private static final class Context {

        private static final URI[] EMPTY = new URI[0];

        final int index;

        URI[] sources;

        boolean used;

        Context(final int index) {
            this.index = index;
            this.sources = EMPTY;
            this.used = false;
        }

    }

    private static final class Record {

        final boolean inverse;

        @Nullable
        final Hash subject;

        final int property;

        final int type;

        @Nullable
        final Hash object;

        final int context;

        private Record(final boolean inverse, final Hash subject, final int predicate,
                final int type, final Hash object, final int context) {
            assert !inverse || object != null;
            assert inverse || subject != null;
            this.inverse = inverse;
            this.subject = subject;
            this.property = predicate;
            this.type = type;
            this.object = object;
            this.context = context;

        }

        public static Record create(final boolean inverse, @Nullable final Hash subject,
                final int predicate, final int type, @Nullable final Hash object,
                @Nullable final int context) {
            return new Record(inverse, subject, predicate, type, object, context);
        }

        @Nullable
        public static Record read(final Reader reader) throws IOException {

            final Hash hash = Hash.read(reader);
            if (hash == null) {
                return null;
            }

            boolean inverse = false;
            Hash subject = null;
            Hash object = null;
            int predicate = -1;
            int type = -1;
            int context = -1;

            final int c = reader.read();
            final boolean hasContext = (c & 0x01) != 0;
            final int format = c & 0xE;

            if (format == 8) {
                // o p c
                inverse = true;
                object = hash;
                predicate = readInt(reader);
            } else if (format == 4) {
                // s t c
                subject = hash;
                type = readInt(reader);
            } else if (format == 2) {
                // s p o c
                subject = hash;
                predicate = readInt(reader);
                object = Hash.read(reader);
            } else {
                throw new Error("format is " + format);
            }

            if (hasContext) {
                context = readInt(reader);
            }

            return create(inverse, subject, predicate, type, object, context);
        }

        public void write(final Writer writer) throws IOException {

            final int flag = this.context >= 0 ? 1 : 0;

            if (this.inverse) {
                // o p c -> hash(o) char(flag) 4*char(p) hash(c)
                this.object.write(writer);
                writer.write(flag + 8);
                writeInt(writer, this.property);
            } else if (this.object == null) {
                // s t c -> hash(s) char(flag) 4*char(type, 127 each) hash(c)
                this.subject.write(writer);
                writer.write(flag + 4);
                writeInt(writer, this.type);
            } else {
                // s p o c -> hash(s) char(flag) 4*char(p) hash(o) hash(c)
                this.subject.write(writer);
                writer.write(flag + 2);
                writeInt(writer, this.property);
                this.object.write(writer);
            }

            if (this.context >= 0) {
                writeInt(writer, this.context);
            }
        }

        private static int readInt(final Reader reader) throws IOException {
            int number = 0;
            int mult = 1;
            for (int i = 0; i < 4; ++i) {
                number += (reader.read() - 1) * mult;
                mult *= 127;
            }
            return number;
        }

        private static void writeInt(final Writer writer, final int number) throws IOException {
            int n = number;
            for (int i = 0; i < 4; ++i) {
                final char ch = (char) (n % 127 + 1);
                writer.write(ch);
                n = n / 127;
            }
        }

    }

    private static final class Hash {

        private static final int MAX_LENGTH = 4 * 1024;

        private static final int TABLE_SIZE = 4 * 1024 - 1;

        private static final Hash[] TABLE_HASHES = new Hash[TABLE_SIZE];

        private static final Value[] TABLE_VALUES = new Value[TABLE_SIZE];

        private static final Index<URI> DATATYPE_INDEX = new Index<URI>(1024);

        private static final Index<String> LANGUAGE_INDEX = new Index<String>(1024);

        private final long lo;

        private final long hi;

        public static Hash read(final Reader reader) throws IOException {
            long lo = 0;
            for (int i = 0; i < 8; ++i) {
                final long c = reader.read();
                if (c < 0) {
                    return null;
                }
                lo = lo | (c & 0xFFL) << 8 * i;
            }
            long hi = 0;
            for (int i = 0; i < 8; ++i) {
                final long c = reader.read() & 0xFFL;
                hi = hi | c << 8 * i;
            }
            return new Hash(lo, hi);
        }

        public static Hash create(final Value value) {
            if (value.stringValue().length() > MAX_LENGTH) {
                return compute(value);
            }
            final int index = (value.hashCode() & 0x7FFFFFFF) % TABLE_SIZE;
            synchronized (TABLE_VALUES) {
                if (value.equals(TABLE_VALUES[index])) {
                    return TABLE_HASHES[index];
                }
            }
            final Hash hash = compute(value);
            synchronized (TABLE_VALUES) {
                TABLE_VALUES[index] = value;
                TABLE_HASHES[index] = hash;
            }
            return hash;
        }

        private static Hash compute(final Value value) {

            final String string = value.stringValue();

            boolean doHash = true;
            long lo = 0;
            long hi = 0;

            final int length = string.length();
            if (length <= 15) {
                doHash = false;
                long cur = 0;
                for (int i = 0; i < 16; ++i) {
                    int c = 1;
                    if (i < length) {
                        c = string.charAt(i);
                        if (c <= 0 || c >= 128) {
                            doHash = true;
                            break;
                        }
                    }
                    cur = cur << 8 | c;
                    if (i == 7) {
                        lo = cur;
                        cur = 0;
                    }
                }
                hi = cur;
            }

            if (doHash) {
                final long[] hc = Util.murmur3(string);
                lo = hc[0];
                hi = hc[1];
                lo = (lo & 0x7F7F7F7F7F7F7F7FL) + 0x0101010101010101L;
                lo = lo & 0x7F7F7F7F7F7F7F7FL | (lo & 0x8080808080808080L) >> 1;
                hi = (hi & 0x7F7F7F7F7F7F7F7FL) + 0x0101010101010101L;
                hi = hi & 0x7F7F7F7F7F7F7F7FL | (hi & 0x8080808080808080L) >> 1;
                hi = hi & 0x0FFFFFFFFFFFFFFFL | 0x4000000000000000L;
            }

            if (value instanceof URI) {
                hi = hi | 0x3000000000000000L;
            } else if (value instanceof BNode) {
                hi = hi | 0x2000000000000000L;
            } else if (value instanceof Literal) {
                hi = hi | 0x1000000000000000L;
                final Literal literal = (Literal) value;
                int index = 0;
                if (literal.getDatatype() != null) {
                    index = DATATYPE_INDEX.put(literal.getDatatype());
                } else if (literal.getLanguage() != null) {
                    index = LANGUAGE_INDEX.put(literal.getLanguage()) | 0x40000000;
                }
                index = index & 0x7FFFFFFF;
                lo = (lo ^ index) & 0xFFFFFFFF7F7F7F7FL;
                if ((lo & 0xFFL) == 0L) {
                    lo = lo | 0x01L;
                }
                if ((lo & 0xFF00L) == 0L) {
                    lo = lo | 0x0100L;
                }
                if ((lo & 0xFF0000L) == 0L) {
                    lo = lo | 0x010000L;
                }
                if ((lo & 0xFF000000L) == 0L) {
                    lo = lo | 0x01000000L;
                }
            }

            return new Hash(lo, hi);
        }

        private Hash(final long lo, final long hi) {
            this.lo = lo;
            this.hi = hi;
        }

        public boolean isURI() {
            return (this.hi & 0x3000000000000000L) == 0x3000000000000000L;
        }

        public boolean isLiteral() {
            return (this.hi & 0x3000000000000000L) == 0x1000000000000000L;
        }

        @Override
        public boolean equals(final Object object) {
            if (object == this) {
                return true;
            }
            if (!(object instanceof Hash)) {
                return false;
            }
            final Hash other = (Hash) object;
            return this.lo == other.lo && this.hi == other.hi;
        }

        @Override
        public int hashCode() {
            final int hh = (int) (this.hi >> 32);
            final int hl = (int) this.hi;
            final int lh = (int) (this.lo >> 32);
            final int ll = (int) this.lo;
            return ((hh * 37 + hl) * 37 + lh) * 37 + ll;
        }

        public void write(final Writer writer) throws IOException {
            long cur = this.lo;
            for (int i = 0; i < 8; ++i) {
                final int ch = (int) cur & 0xFF;
                writer.write(ch);
                cur = cur >> 8;
            }
            cur = this.hi;
            for (int i = 0; i < 8; ++i) {
                final int ch = (int) cur & 0xFF;
                writer.write(ch);
                cur = cur >> 8;
            }
        }

    }

    private static final class Index<T> {

        private final Map<T, Integer> map;

        private final List<T> list;

        private final int size;

        Index(final int size) {
            final int capacity = Math.min(size, 1024);
            this.map = new HashMap<T, Integer>(capacity);
            this.list = new ArrayList<T>(capacity);
            this.size = size;
        }

        @Nullable
        synchronized Integer put(final T element) {
            Integer index = this.map.get(element);
            if (index == null && this.list.size() < this.size) {
                index = this.list.size() + 1;
                this.list.add(element);
                this.map.put(element, index);
            }
            return index;
        }

        @Nullable
        synchronized T get(final int index) {
            return this.list.get(index - 1);
        }

    }

}