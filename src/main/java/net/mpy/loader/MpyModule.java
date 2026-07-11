package net.mpy.loader;

/**
 * A fully loaded .mpy module: the header fields, the module-level qstr table
 * (resolved to strings), the constant object table, and the root code object.
 *
 * The bytecode of any code object references entries here by index:
 * FORMAT_QSTR opcodes index {@link #qstrTable}; LOAD_CONST_OBJ indexes
 * {@link #objTable}.
 */
public final class MpyModule {

    public final int version;        // header[1], must be MPY_VERSION (6)
    public final int subVersion;     // feature byte & 3
    public final int arch;           // (feature byte >> 2) & 0x2f; 0 == none (pure bytecode)
    public final int smallIntBits;   // header[3]

    public final String[] qstrTable; // resolved module qstr table (index -> string)
    public final Object[] objTable;  // constant object table (index -> PyObj value)
    public final CodeObject root;    // top-level module code
    /** SHA-256 of the .mpy bytes (hex); snapshots use it to verify that the same
     *  module bytes are present before restoring code references into them. */
    public String sha256;
    /** The raw .mpy bytes this module was loaded from - snapshots embed them so a
     *  restore is fully self-contained. */
    public byte[] rawBytes;
    /** Runtime hook: the namespace of the PyModule built from this code (set by
     *  the VM at import / snapshot-restore). Frames from this module resolve
     *  their global scope here; null means the VM's main globals. */
    public java.util.Map<String, Object> ns;

    public MpyModule(int version, int subVersion, int arch, int smallIntBits,
                     String[] qstrTable, Object[] objTable, CodeObject root) {
        this.version = version;
        this.subVersion = subVersion;
        this.arch = arch;
        this.smallIntBits = smallIntBits;
        this.qstrTable = qstrTable;
        this.objTable = objTable;
        this.root = root;
    }

    public boolean isBytecodeOnly() { return arch == 0; }

    /** Resolve a module qstr-table index to its string, tolerating out-of-range. */
    public String qstr(int index) {
        return (index >= 0 && index < qstrTable.length) ? qstrTable[index] : ("<qstr?" + index + ">");
    }
}
