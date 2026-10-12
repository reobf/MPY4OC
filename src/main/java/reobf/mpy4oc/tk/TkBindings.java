package reobf.mpy4oc.tk;

import java.util.Arrays;
import java.util.List;

final class TkBindings {
    static final List<String> SEQUENCES = Arrays.asList("<Button-1>", "<ButtonRelease-1>", "<Button-3>", "<ButtonRelease-3>",
            "<Motion>", "<B1-Motion>", "<MouseWheel>", "<Key>", "<KeyRelease>", "<Return>", "<space>",
            "<Tab>", "<BackSpace>", "<Delete>", "<Left>", "<Right>", "<Home>", "<End>", "<<Paste>>", "<<Copy>>", "<<Cut>>", "<<ListboxSelect>>", "<Configure>",
            "<Up>", "<Down>", "<Prior>", "<Next>", "<Double-Button-1>");
    private TkBindings() {}
    static void validate(String sequence) {
        if (!SEQUENCES.contains(sequence)) throw new IllegalArgumentException("unsupported event: " + sequence);
    }
}
