package eu.fbk.rdfpro.rules.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;
import javax.xml.datatype.XMLGregorianCalendar;

import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.Namespace;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.datatypes.XMLDatatypeUtil;
import org.openrdf.model.impl.NamespaceImpl;
import org.openrdf.model.util.URIUtil;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.SESAME;
import org.openrdf.model.vocabulary.XMLSchema;

import eu.fbk.rdfpro.rules.util.Iterators;

final class MemoryQuadModel extends QuadModel {

    private static final int INITIAL_VALUE_TABLE_SIZE = 256 - 1;

    private static final int INITIAL_STATEMENT_TABLE_SIZE = 256 - 1;

    private static final ModelURI NULL_VALUE = new ModelURI(null, "sesame:empty");

    private static final ModelStatement NULL_STATEMENT = new ModelStatement(NULL_VALUE,
            NULL_VALUE, NULL_VALUE, NULL_VALUE);

    private static final int SUBJ = 0;

    private static final int PRED = 1;

    private static final int OBJ = 2;

    private static final int CTX = 3;

    private static final long serialVersionUID = 1L;

    private final Map<String, Namespace> namespaces;

    private ModelValue[] valueTable;

    private int valueCount;

    private int valueSlots;

    private final ModelURI valueNil;

    private ModelStatement[] statementTable;

    private int statementSlots;

    private int statementCount;

    public MemoryQuadModel() {
        this.namespaces = new HashMap<>();
        this.valueTable = new ModelValue[INITIAL_VALUE_TABLE_SIZE];
        this.valueCount = 0;
        this.valueSlots = 0;
        this.statementTable = new ModelStatement[INITIAL_STATEMENT_TABLE_SIZE];
        this.statementCount = 0;
        this.statementSlots = 0;
        this.valueNil = (ModelURI) lookupValue(SESAME.NIL, true);
    }

    // NAMESPACE HANDLING

    @Override
    protected Set<Namespace> doGetNamespaces() {
        return new HashSet<>(this.namespaces.values());
    }

    @Override
    protected Namespace doGetNamespace(final String prefix) {
        return this.namespaces.get(prefix);

    }

    @Override
    protected Namespace doSetNamespace(final String prefix, @Nullable final String name) {
        if (name == null) {
            return this.namespaces.remove(prefix);
        } else {
            return this.namespaces.put(prefix, new NamespaceImpl(prefix, name));
        }
    }

    // STATEMENT HANDLING - CONTEXT ARRAYS
    //
    // the following methods performs three things:
    // (1) they translate input values to model values, possibly creating them
    // (2) they translate doXXX calls supplying multiple contexts to corresponding doXXX calls
    // operating on a single context (possibly a wildcard)

    @Override
    protected int doSize(@Nullable final Resource subj, @Nullable final URI pred,
            @Nullable final Value obj, final Resource[] ctxs) {

        // Null context arrays are forbidden
        Objects.requireNonNull(ctxs);

        // Lookup SPO in the values hash table
        final ModelResource msubj = (ModelResource) lookupValue(subj, false);
        final ModelURI mpred = (ModelURI) lookupValue(pred, false);
        final ModelValue mobj = lookupValue(obj, false);

        // If one of SPO is missing in the table, then no statements can exist for that component
        if (msubj == NULL_VALUE || mpred == NULL_VALUE || mobj == NULL_VALUE) {
            return 0;
        }

        // Otherwise, handle two cases based on the contents of the context array
        if (ctxs.length == 0) {
            // (1) Match any context
            return doSize(msubj, mpred, mobj, (ModelResource) null);

        } else {
            // (2) Match multiple contexts, summing the # of statements for each of them
            int size = 0;
            for (final Resource ctx : ctxs) {
                final ModelResource mctx = ctx == null ? this.valueNil
                        : (ModelResource) lookupValue(ctx, false);
                size += mctx == NULL_VALUE ? 0 : doSize(msubj, mpred, mobj, mctx);
            }
            return size;
        }
    }

    @Override
    protected Iterator<Statement> doIterator(@Nullable final Resource subj,
            @Nullable final URI pred, @Nullable final Value obj, final Resource[] ctxs) {

        // Null context arrays are forbidden
        Objects.requireNonNull(ctxs);

        // Lookup SPO in the values hash table
        final ModelResource msubj = (ModelResource) lookupValue(subj, false);
        final ModelURI mpred = (ModelURI) lookupValue(pred, false);
        final ModelValue mobj = lookupValue(obj, false);

        // If any of SPO is missing in the table, then no statements can exist for that component
        if (msubj == NULL_VALUE || mpred == NULL_VALUE || mobj == NULL_VALUE) {
            return Collections.emptyIterator();
        }

        // Otherwise handle three cases based on the contexts array
        if (ctxs.length == 0) {
            // (1) Match any context
            return doIterator(msubj, mpred, mobj, (ModelResource) null);

        } else if (ctxs.length == 1) {
            // (2) Match exactly one context. If not defined, return an empty iterator
            final ModelResource mctx = ctxs[0] == null ? this.valueNil
                    : (ModelResource) lookupValue(ctxs[0], false);
            return mctx == NULL_VALUE ? Collections.emptyIterator() //
                    : doIterator(msubj, mpred, mobj, mctx);

        } else {
            // (3) Match multiple contexts, concatenating the iterators for each context
            final Iterator<Resource> ctxIterator = Arrays.asList(ctxs).iterator();
            return Iterators.concat(Iterators.transform(ctxIterator, (final Resource ctx) -> {
                final ModelResource mctx = ctx == null ? this.valueNil
                        : (ModelResource) lookupValue(ctx, false);
                return ctx == NULL_VALUE ? Collections.emptyIterator() //
                        : doIterator(msubj, mpred, mobj, mctx);
            }));
        }
    }

    @Override
    protected boolean doAdd(final Resource subj, final URI pred, final Value obj,
            final Resource[] ctxs) {

        // All SPOC components must be specified
        Objects.requireNonNull(subj);
        Objects.requireNonNull(pred);
        Objects.requireNonNull(obj);
        Objects.requireNonNull(ctxs);

        // Lookup SPO model values in the values hash table, creating them if necessary
        final ModelResource msubj = (ModelResource) lookupValue(subj, true);
        final ModelURI mpred = (ModelURI) lookupValue(pred, true);
        final ModelValue mobj = lookupValue(obj, true);

        // Handle two cases based on the context array
        if (ctxs.length == 0) {
            // (1) Add a single statement in the default context (sesame:nil)
            return doAdd(msubj, mpred, mobj, this.valueNil);

        } else {
            // (2) Add multiple statements in different contexts
            boolean modified = false;
            for (final Resource ctx : ctxs) {
                final ModelResource mctx = ctx == null ? this.valueNil
                        : (ModelResource) lookupValue(ctx, true);
                final boolean isNew = doAdd(msubj, mpred, mobj, mctx);
                modified |= isNew;
            }
            return modified;
        }
    }

    @Override
    protected boolean doRemove(@Nullable final Resource subj, @Nullable final URI pred,
            @Nullable final Value obj, final Resource[] ctxs) {

        // Null context arrays are forbidden
        Objects.requireNonNull(ctxs);

        // Lookup SPO in the values hash table
        final ModelResource msubj = (ModelResource) lookupValue(subj, false);
        final ModelURI mpred = (ModelURI) lookupValue(pred, false);
        final ModelValue mobj = lookupValue(obj, false);

        // If any of SPO is missing in the table, then no statements can exist for that component
        if (msubj == NULL_VALUE || mpred == NULL_VALUE || mobj == NULL_VALUE) {
            return false;
        }

        // Otherwise handle two cases based on the contents of the contexts array
        if (ctxs.length == 0) {
            // (1) Wildcard context: remove statements matching SPO in any context
            return doRemove(msubj, mpred, mobj, (ModelResource) null);

        } else {
            // (2) Specific contexts: remove statements matching SPO in the given contexts
            boolean modified = false;
            for (final Resource ctx : ctxs) {
                final ModelResource mctx = ctx == null ? this.valueNil
                        : (ModelResource) lookupValue(ctx, false);
                if (mctx != NULL_VALUE) {
                    final boolean m = doRemove(msubj, mpred, mobj, mctx);
                    modified |= m;
                }
            }
            return modified;
        }
    }

    // STATEMENT HANDLING - SINGLE CONTEXTS

    private int doSize(@Nullable final ModelResource subj, @Nullable final ModelURI pred,
            @Nullable final ModelValue obj, @Nullable final ModelResource ctx) {

        // Select the SPOC component associated to the minimum number of statements
        final int comp = selectComponent(subj, pred, obj, ctx);

        // If no component has been specified, return the total model size
        if (comp < 0) {
            return this.statementCount;
        }

        // Otherwise, iterate over the statements associated to the component, applying the filter
        // and returning the number of statements that matches it
        int size = 0;
        final ModelValue value = comp == 0 ? subj : comp == 1 ? pred : comp == 2 ? obj : ctx;
        for (ModelStatement stmt = value.next(comp); stmt != null; stmt = stmt.next(comp)) {
            if (stmt.match(subj, pred, obj, ctx)) {
                ++size;
            }
        }
        return size;
    }

    private Iterator<Statement> doIterator(@Nullable final ModelResource subj,
            @Nullable final ModelURI pred, @Nullable final ModelValue obj,
            @Nullable final ModelResource ctx) {

        // Select the SPOC component associated to the min number of statements
        final int comp = selectComponent(subj, pred, obj, ctx);

        // If no component has been specified, return an iterator over all the statements
        // The returned iterator supports element removal (delegating to removeStatement)
        if (comp < 0) {
            return new Iterator<Statement>() {

                private int index = 0;

                private ModelStatement next = null;

                private ModelStatement last = null;

                @Override
                public boolean hasNext() {
                    if (this.next != null) {
                        return true;
                    }
                    for (; this.index < MemoryQuadModel.this.statementTable.length; ++this.index) {
                        final ModelStatement stmt = MemoryQuadModel.this.statementTable[this.index];
                        if (stmt != null && stmt != NULL_STATEMENT) {
                            this.next = stmt;
                            return true;
                        }
                    }
                    return false;
                }

                @Override
                public Statement next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    this.last = this.next;
                    this.next = null;
                    return this.last;
                }

                @Override
                public void remove() {
                    if (this.last == null) {
                        throw new NoSuchElementException();
                    }
                    removeStatement(this.last.subj, this.last.pred, this.last.obj, this.last.ctx);
                    this.last = null;
                }

            };
        }

        // Otherwise, build an iterator over all the statements associated to the component, and
        // then filter it so to return only statements matching the supplied filter. The returned
        // iterator supports statement removal (by delegating to removeStatement)
        final ModelValue value = comp == 0 ? subj : comp == 1 ? pred : comp == 2 ? obj : ctx;
        final ModelStatement firstStmt = value.next(comp);
        if (firstStmt == null) {
            return Collections.emptyIterator();
        }
        final Iterator<ModelStatement> iterator = new Iterator<ModelStatement>() {

            private ModelStatement next = firstStmt;

            private ModelStatement last = null;

            @Override
            public boolean hasNext() {
                return this.next != null;
            }

            @Override
            public ModelStatement next() {
                this.last = this.next;
                this.next = this.next.next(comp);
                return this.last;
            }

            @Override
            public void remove() {
                if (this.last == null) {
                    throw new NoSuchElementException();
                }
                removeStatement(this.last.subj, this.last.pred, this.last.obj, this.last.ctx);
                this.last = null;
            }

        };
        return Iterators.filter(iterator, (final ModelStatement stmt) -> {
            return stmt.match(subj, pred, obj, ctx);
        });
    }

    private boolean doAdd(final ModelResource subj, final ModelURI pred, final ModelValue obj,
            final ModelResource ctx) {

        // Identify the first slot where the statement could be stored in the hash table
        final int hash = ModelStatement.hash(subj, pred, obj, ctx);
        int slot = Math.abs(hash) % this.statementTable.length;

        // Scan the hash table (linear probing), doing nothing if a matching statement is found or
        // adding the statement otherwise
        while (true) {

            // Retrieve the statement for the current slot (if any) and handle three cases
            ModelStatement stmt = this.statementTable[slot];
            final boolean isNull = stmt == null;
            if (isNull || stmt == NULL_STATEMENT) {

                // (1) Empty/deleted slot: add the statement
                // First add the statement to the hash table and rehash if necessary
                stmt = new ModelStatement(subj, pred, obj, ctx);
                this.statementTable[slot] = stmt;
                if (isNull) {
                    ++this.statementSlots;
                    if (this.statementSlots * 2 >= this.statementTable.length) {
                        rehashStatements();
                    }
                }

                // Then connect the statement to the various linked lists
                if (subj.nextBySubj != null) {
                    stmt.nextBySubj = subj.nextBySubj;
                    subj.nextBySubj.prevBySubj = stmt;
                }
                if (pred.nextByPred != null) {
                    stmt.nextByPred = pred.nextByPred;
                    pred.nextByPred.prevByPred = stmt;
                }
                if (obj.nextByObj != null) {
                    stmt.nextByObj = obj.nextByObj;
                    obj.nextByObj.prevByObj = stmt;
                }
                if (ctx.nextByCtx != null) {
                    stmt.nextByCtx = ctx.nextByCtx;
                    ctx.nextByCtx.prevByCtx = stmt;
                }
                subj.nextBySubj = stmt;
                pred.nextByPred = stmt;
                obj.nextByObj = stmt;
                ctx.nextByCtx = stmt;

                // Increment statement counters
                ++this.statementCount;
                ++subj.numSubj;
                ++pred.numPred;
                ++obj.numObj;
                ++ctx.numCtx;

                // Signal a statement was added
                return true;

            } else if (subj == stmt.subj && pred == stmt.pred && obj == stmt.obj
                    && ctx == stmt.ctx) {

                // (2) Statement already in the model: abort signalling nothing happened
                return false;

            } else {

                // (3) Another statement in the slot: move to next slot
                slot = incrementSlot(slot, this.statementTable.length);

            }
        }
    }

    private boolean doRemove(@Nullable final ModelResource subj, @Nullable final ModelURI pred,
            @Nullable final ModelValue obj, @Nullable final ModelResource ctx) {

        // Do nothing if model is empty
        if (this.statementCount == 0) {
            return false;
        }

        // Remove exactly one statement (at most) if all the components were supplied
        if (subj != null && pred != null && obj != null && ctx != null) {
            return removeStatement(subj, pred, obj, ctx);
        }

        // Select the SPOC component associated to the min number of statements
        final int comp = selectComponent(subj, pred, obj, ctx);

        // Clear the whole model (preserving generated values) if no component was specified
        if (comp < 0) {
            this.statementTable = new ModelStatement[INITIAL_STATEMENT_TABLE_SIZE];
            this.statementCount = 0;
            this.statementSlots = 0;
            for (final ModelValue value : this.valueTable) {
                if (value != null) {
                    value.nextByObj = null;
                    value.numObj = 0;
                    if (value instanceof ModelResource) {
                        final ModelResource resource = (ModelResource) value;
                        resource.nextBySubj = null;
                        resource.numSubj = 0;
                        resource.nextByCtx = null;
                        resource.numCtx = 0;
                        if (value instanceof ModelURI) {
                            final ModelURI uri = (ModelURI) value;
                            uri.nextByPred = null;
                            uri.numPred = 0;
                        }
                    }
                }
            }
            return true;
        }

        // Remove all the statements associated to the component that satisfy the filter
        boolean modified = false;
        final ModelValue value = comp == 0 ? subj : comp == 1 ? pred : comp == 2 ? obj : ctx;
        ModelStatement stmt = value.next(comp);
        while (stmt != null) {
            final ModelStatement next = stmt.next(comp);
            if (stmt.match(subj, pred, obj, ctx)) {
                final boolean m = removeStatement(stmt.subj, stmt.pred, stmt.obj, stmt.ctx);
                modified |= m;
            }
            stmt = next;
        }
        return modified;
    }

    // STATEMENT HANDLING - MISC METHODS

    private boolean removeStatement(final ModelResource subj, final ModelURI pred,
            final ModelValue obj, final ModelResource ctx) {

        // Delete a matching statement from the hash table, aborting if it does not exist
        final int hash = ModelStatement.hash(subj, pred, obj, ctx);
        int slot = Math.abs(hash) % this.statementTable.length;
        ModelStatement mstmt;
        while (true) {
            mstmt = this.statementTable[slot];
            if (mstmt == null) {
                return false;
            } else if (mstmt != NULL_STATEMENT && subj == mstmt.subj && pred == mstmt.pred
                    && obj == mstmt.obj && ctx == mstmt.ctx) {
                this.statementTable[slot] = NULL_STATEMENT;
                --this.statementCount;
                break;
            } else {
                slot = incrementSlot(slot, this.statementTable.length);
            }
        }

        // Delete from subject doubly-linked list
        if (mstmt.prevBySubj != null) {
            mstmt.prevBySubj.nextBySubj = mstmt.nextBySubj;
        } else {
            subj.nextBySubj = mstmt.nextBySubj;
        }
        if (mstmt.nextBySubj != null) {
            mstmt.nextBySubj.prevBySubj = mstmt.prevBySubj;
        }

        // Delete from predicate doubly-linked list
        if (mstmt.prevByPred != null) {
            mstmt.prevByPred.nextByPred = mstmt.nextByPred;
        } else {
            pred.nextByPred = mstmt.nextByPred;
        }
        if (mstmt.nextByPred != null) {
            mstmt.nextByPred.prevByPred = mstmt.prevByPred;
        }

        // Delete from object doubly-linked list
        if (mstmt.prevByObj != null) {
            mstmt.prevByObj.nextByObj = mstmt.nextByObj;
        } else {
            obj.nextByObj = mstmt.nextByObj;
        }
        if (mstmt.nextByObj != null) {
            mstmt.nextByObj.prevByObj = mstmt.prevByObj;
        }

        // Delete from context doubly-linked list
        if (mstmt.prevByCtx != null) {
            mstmt.prevByCtx.nextByCtx = mstmt.nextByCtx;
        } else {
            ctx.nextByCtx = mstmt.nextByCtx;
        }
        if (mstmt.nextByCtx != null) {
            mstmt.nextByCtx.prevByCtx = mstmt.prevByCtx;
        }

        // Update counters
        --subj.numSubj;
        --pred.numPred;
        --obj.numObj;
        --ctx.numCtx;
        --this.statementCount;

        // Signal that a statement was removed
        return true;
    }

    private ModelValue lookupValue(@Nullable final Value value, final boolean canCreate) {

        // Handle null case
        if (value == null) {
            return null;
        }

        // Handle the case the value is already a model value belonging to this model
        if (value instanceof ModelValue) {
            final ModelValue mv = (ModelValue) value;
            if (mv.model() == this) {
                return mv;
            }
        }

        // Lookup the model value in the hash table
        final int hash = value.hashCode();
        int slot = Math.abs(hash) % this.valueTable.length;
        while (true) {
            ModelValue mv = this.valueTable[slot];
            final boolean isNull = mv == null;
            if (isNull || mv == NULL_VALUE) {

                // Return null if missing and cannot create
                if (!canCreate) {
                    return NULL_VALUE;
                }

                // Otherwise create the model value
                if (value instanceof URI) {
                    mv = new ModelURI(this, value.stringValue());
                } else if (value instanceof BNode) {
                    mv = new ModelBNode(this, ((BNode) value).getID());
                } else if (value instanceof Literal) {
                    final Literal lit = (Literal) value;
                    final URI datatype = lit.getLanguage() != null ? RDF.LANGSTRING //
                            : lit.getDatatype() != null ? lit.getDatatype() : XMLSchema.STRING;
                    mv = new ModelLiteral(this, lit.getLabel(), lit.getLanguage().intern(),
                            (ModelURI) lookupValue(datatype, true));
                } else {
                    throw new Error(value.getClass().getName());
                }

                // Add the model value to the hash table and rehash if necessary
                this.valueTable[slot] = mv;
                ++this.valueCount;
                if (isNull) {
                    ++this.valueSlots;
                    if (this.valueSlots * 2 >= this.valueTable.length) {
                        rehashValues();
                    }
                }

                // Return inserted model value
                return mv;

            } else if (mv.equals(value)) {

                // Value already in the table: return it
                return mv;

            } else {

                // Move to next slot
                slot = incrementSlot(slot, this.valueTable.length);

            }
        }
    }

    private void rehashValues() {

        // Abort if there is no need for rehashing
        if (this.valueSlots < this.valueTable.length / 2) {
            return;
        }

        // Compute the new table size based on the number of stored values and NOT on the
        // number of filled slots (which may contain the NULL_VALUE marker)
        final int newLength = this.valueTable.length
                * (this.valueCount * 2 >= this.valueTable.length ? 2 : 1);

        // Replace the statement hash table with a possibly larger table
        final ModelValue[] oldTable = this.valueTable;
        this.valueTable = new ModelValue[newLength];
        for (final ModelValue mv : oldTable) {
            if (mv != null && mv != NULL_VALUE) {
                final int hash = mv.hashCode();
                int slot = Math.abs(hash) % this.valueTable.length;
                while (this.valueTable[slot] != null) {
                    slot = incrementSlot(slot, this.valueTable.length);
                }
                this.valueTable[slot] = mv;
            }
        }

        // The number of used slots is now equal to the number of stored values
        this.valueSlots = this.valueCount;
    }

    private void rehashStatements() {

        // Abort if there is no need for re-hashing
        if (this.statementSlots * 2 < this.statementTable.length) {
            return;
        }

        // Compute the new table size based on the number of stored statements and NOT on the
        // number of filled slots (which may contain the NULL_STATEMENT marker)
        final int newLength = this.statementTable.length
                * (this.statementCount * 2 >= this.statementTable.length ? 2 : 1);

        // Replace the statement hash table with a possibly larger table
        final ModelStatement[] oldTable = this.statementTable;
        this.statementTable = new ModelStatement[newLength];
        for (final ModelStatement mstmt : oldTable) {
            if (mstmt != null && mstmt != NULL_STATEMENT) {
                final int hash = mstmt.hash();
                int slot = Math.abs(hash) % this.statementTable.length;
                while (this.statementTable[slot] != null) {
                    slot = incrementSlot(slot, this.statementTable.length);
                }
                this.statementTable[slot] = mstmt;
            }
        }

        // The number of used slots is now equal to the number of stored statements
        this.statementSlots = this.statementCount;
    }

    private static int selectComponent(@Nullable final ModelResource subj,
            @Nullable final ModelURI pred, @Nullable final ModelValue obj,
            @Nullable final ModelResource ctx) {

        // Start with no component selected
        int result = -1;
        int num = Integer.MAX_VALUE;

        // Then, choose the component with the minimum number of associated statements
        if (subj != null && subj.numSubj < num) {
            result = SUBJ;
            num = subj.numSubj;
        }
        if (pred != null && pred.numPred < num) {
            result = PRED;
            num = pred.numPred;
        }
        if (obj != null && obj.numObj < num) {
            result = OBJ;
            num = obj.numObj;
        }
        if (ctx != null && ctx.numCtx < num) {
            result = CTX;
            num = ctx.numCtx;
        }

        // Return either a component ID or a negative value if no component was specified
        return result;
    }

    private static int incrementSlot(final int num, final int max) {
        final int result = num + 1;
        return result >= max ? 0 : result;
    }

    private static abstract class ModelValue implements Value {

        private static final long serialVersionUID = 1L;

        @Nullable
        transient ModelStatement nextByObj;

        transient int numObj;

        ModelStatement next(final int component) {
            switch (component) {
            case OBJ:
                return this.nextByObj;
            default:
                throw new Error();
            }
        }

        abstract MemoryQuadModel model();

    }

    private static abstract class ModelResource extends ModelValue implements Resource {

        private static final long serialVersionUID = 1L;

        @Nullable
        transient ModelStatement nextBySubj;

        @Nullable
        transient ModelStatement nextByCtx;

        transient int numSubj;

        transient int numCtx;

        @Override
        ModelStatement next(final int component) {
            switch (component) {
            case SUBJ:
                return this.nextBySubj;
            case OBJ:
                return this.nextByObj;
            case CTX:
                return this.nextByCtx;
            default:
                throw new Error();
            }
        }

    }

    private static final class ModelURI extends ModelResource implements URI {

        private static final long serialVersionUID = 1L;

        private final transient MemoryQuadModel model;

        private final String string;

        private @Nullable String namespace;

        private @Nullable String localName;

        @Nullable
        ModelStatement nextByPred;

        int numPred;

        ModelURI(final MemoryQuadModel model, final String string) {
            this.model = model;
            this.string = string;
        }

        @Override
        ModelStatement next(final int component) {
            switch (component) {
            case SUBJ:
                return this.nextBySubj;
            case PRED:
                return this.nextByPred;
            case OBJ:
                return this.nextByObj;
            case CTX:
                return this.nextByCtx;
            default:
                throw new Error();
            }
        }

        @Override
        MemoryQuadModel model() {
            return this.model;
        }

        private void splitIfNecessary() {
            if (this.namespace == null) {
                final int index = URIUtil.getLocalNameIndex(this.string);
                this.namespace = this.string.substring(0, index).intern();
                this.localName = this.string.substring(index);
            }
        }

        @Override
        public String getNamespace() {
            splitIfNecessary();
            return this.namespace;
        }

        @Override
        public String getLocalName() {
            splitIfNecessary();
            return this.localName;
        }

        @Override
        public String stringValue() {
            return this.string;
        }

        @Override
        public boolean equals(final Object object) {
            if (object == this) {
                return true;
            }
            if (this.model != null && object instanceof ModelURI
                    && this.model == ((ModelURI) object).model) {
                return false;
            }
            if (object instanceof URI) {
                return this.string.equals(object.toString());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.string.hashCode();
        }

        @Override
        public String toString() {
            return this.string;
        }

    }

    private static final class ModelBNode extends ModelResource implements BNode {

        private static final long serialVersionUID = 1L;

        private final transient MemoryQuadModel model;

        private final String id;

        public ModelBNode(final MemoryQuadModel model, final String id) {
            this.model = model;
            this.id = id;
        }

        @Override
        MemoryQuadModel model() {
            return this.model;
        }

        @Override
        public String getID() {
            return this.id;
        }

        @Override
        public String stringValue() {
            return this.id;
        }

        @Override
        public boolean equals(final Object object) {
            if (object == this) {
                return true;
            }
            if (this.model != null && object instanceof ModelBNode
                    && this.model == ((ModelBNode) object).model) {
                return false;
            }
            if (object instanceof BNode) {
                return this.id.equals(((BNode) object).getID());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.id.hashCode();
        }

        @Override
        public String toString() {
            return "_:" + this.id;
        }

    }

    private static final class ModelLiteral extends ModelValue implements Literal {

        private static final long serialVersionUID = 1L;

        private final transient MemoryQuadModel model;

        private final String label;

        @Nullable
        private final String language;

        @Nullable
        private final ModelURI datatype;

        ModelLiteral(final MemoryQuadModel model, final String label,
                @Nullable final String language, final ModelURI datatype) {
            this.model = model;
            this.label = label;
            this.language = language;
            this.datatype = datatype;
        }

        @Override
        MemoryQuadModel model() {
            return this.model;
        }

        @Override
        public String getLabel() {
            return this.label;
        }

        @Override
        public String getLanguage() {
            return this.language;
        }

        @Override
        public URI getDatatype() {
            return this.datatype;
        }

        @Override
        public String stringValue() {
            return this.label;
        }

        @Override
        public boolean booleanValue() {
            return XMLDatatypeUtil.parseBoolean(getLabel());
        }

        @Override
        public byte byteValue() {
            return XMLDatatypeUtil.parseByte(getLabel());
        }

        @Override
        public short shortValue() {
            return XMLDatatypeUtil.parseShort(getLabel());
        }

        @Override
        public int intValue() {
            return XMLDatatypeUtil.parseInt(getLabel());
        }

        @Override
        public long longValue() {
            return XMLDatatypeUtil.parseLong(getLabel());
        }

        @Override
        public float floatValue() {
            return XMLDatatypeUtil.parseFloat(getLabel());
        }

        @Override
        public double doubleValue() {
            return XMLDatatypeUtil.parseDouble(getLabel());
        }

        @Override
        public BigInteger integerValue() {
            return XMLDatatypeUtil.parseInteger(getLabel());
        }

        @Override
        public BigDecimal decimalValue() {
            return XMLDatatypeUtil.parseDecimal(getLabel());
        }

        @Override
        public XMLGregorianCalendar calendarValue() {
            return XMLDatatypeUtil.parseCalendar(getLabel());
        }

        @Override
        public boolean equals(final Object object) {
            if (object == this) {
                return true;
            }
            if (this.model != null && object instanceof ModelLiteral
                    && this.model == ((ModelBNode) object).model) {
                return false;
            }
            if (object instanceof Literal) {
                final Literal l = (Literal) object;
                return this.label.equals(l.getLabel())
                        && Objects.equals(this.datatype, l.getDatatype())
                        && Objects.equals(this.language, l.getLanguage());
            }
            return false;
        }

        @Override
        public int hashCode() {
            return this.label.hashCode();
        }

        @Override
        public String toString() {
            return this.label;
        }

    }

    private static final class ModelStatement implements Statement {

        private static final long serialVersionUID = 1L;

        final ModelResource subj;

        final ModelURI pred;

        final ModelValue obj;

        final ModelResource ctx;

        @Nullable
        transient ModelStatement prevBySubj;

        @Nullable
        transient ModelStatement prevByPred;

        @Nullable
        transient ModelStatement prevByObj;

        @Nullable
        transient ModelStatement prevByCtx;

        @Nullable
        transient ModelStatement nextBySubj;

        @Nullable
        transient ModelStatement nextByPred;

        @Nullable
        transient ModelStatement nextByObj;

        @Nullable
        transient ModelStatement nextByCtx;

        ModelStatement(final ModelResource subj, final ModelURI pred, final ModelValue obj,
                final ModelResource ctx) {
            this.subj = subj;
            this.pred = pred;
            this.obj = obj;
            this.ctx = ctx;
        }

        @Nullable
        ModelStatement next(final int component) {

            switch (component) {
            case SUBJ:
                return this.nextBySubj;
            case PRED:
                return this.nextByPred;
            case OBJ:
                return this.nextByObj;
            case CTX:
                return this.nextByCtx;
            default:
                throw new Error();
            }
        }

        boolean match(@Nullable final ModelResource subj, @Nullable final ModelURI mpred,
                @Nullable final ModelValue mobj, @Nullable final ModelResource mctx) {
            return (subj == null || subj == this.subj) && (mpred == null || mpred == this.pred)
                    && (mobj == null || mobj == this.obj) && (mctx == null || mctx == this.ctx);
        }

        int hash() {
            return hash(this.subj, this.pred, this.obj, this.ctx);
        }

        static int hash(final ModelResource subj, final ModelURI pred, final ModelValue obj,
                final ModelResource ctx) {
            return 6661 * System.identityHashCode(subj) + 961 * System.identityHashCode(pred) + 31
                    * System.identityHashCode(obj) + System.identityHashCode(ctx);
        }

        @Override
        public Resource getSubject() {
            return this.subj;
        }

        @Override
        public URI getPredicate() {
            return this.pred;
        }

        @Override
        public Value getObject() {
            return this.obj;
        }

        @Override
        public Resource getContext() {
            return this.ctx;
        }

        @Override
        public boolean equals(final Object object) {
            if (object == this) {
                return true;
            }
            if (!(object instanceof Statement)) {
                return false;
            }
            final Statement other = (Statement) object;
            return this.obj.equals(other.getObject()) && this.subj.equals(other.getSubject())
                    && this.pred.equals(other.getPredicate());
        }

        @Override
        public int hashCode() {
            return 961 * this.subj.hashCode() + 31 * this.pred.hashCode() + this.obj.hashCode();
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder(256);
            builder.append("(").append(this.subj).append(", ").append(this.pred).append(", ")
                    .append(this.obj).append(")");
            if (!SESAME.NIL.equals(this.ctx)) {
                builder.append(" [").append(this.ctx).append("]");
            }
            return builder.toString();
        }

    }

}
