package com.yumelium.yumelium.shaders.pack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Iris/Oculus port — a minimal C-preprocessor conditional evaluator. Given an already-assembled shader source (includes
 * inlined, {@code #define}s present, options applied), it strips the {@code #if}/{@code #ifdef}/{@code #ifndef}/
 * {@code #elif}/{@code #else}/{@code #endif} branches that evaluate false, returning only the lines the GL compiler would
 * actually keep.
 *
 * <p>Why this exists: a pack gates its {@code /* DRAWBUFFERS:.. *}{@code /} / {@code RENDERTARGETS} directive by config —
 * e.g. Complementary's {@code composite4} writes {@code DRAWBUFFERS:3} always but {@code DRAWBUFFERS:30} only under
 * {@code #if MOTION_BLUR_EFFECT == 1}. Parsing the raw source (longest-directive heuristic) then attaches colortex0 even
 * when motion blur is OFF, so the pipeline writes garbage to it and ping-pong-flips it — corrupting the scene buffer (the
 * "everything blurs with motion blur off" bug). Stripping inactive branches first makes the directive parse the buffers
 * the active shader really writes.</p>
 *
 * <p>Scope: object-like {@code #define}s (valued + valueless) and the operators real packs use in {@code #if} directive
 * guards ({@code defined}, {@code ! ~ * / % + - << >> < <= > >= == != & ^ | && ||}, parentheses, integer literals).
 * Function-like macros are ignored (not used in these guards); an unknown identifier evaluates to 0, matching the C rule.</p>
 */
public final class GlslConditionals {
    private GlslConditionals() {
    }

    private static final class Frame {
        boolean parentActive; // all enclosing frames active
        boolean anyTaken;     // some branch in this if/elif/else chain already taken
        boolean active;       // this branch active (and parentActive)
    }

    /** @return {@code source} with the false {@code #if}/… branches removed (their lines dropped). */
    public static String stripInactive(String source) {
        Map<String, String> macros = new HashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        StringBuilder out = new StringBuilder(source.length());

        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            boolean active = stack.isEmpty() || stack.peek().active;

            if (trimmed.startsWith("#")) {
                String directive = trimmed.substring(1).trim();
                if (directive.startsWith("ifdef")) {
                    boolean cond = active && macros.containsKey(firstToken(directive.substring(5)));
                    push(stack, active, cond);
                    continue;
                } else if (directive.startsWith("ifndef")) {
                    boolean cond = active && !macros.containsKey(firstToken(directive.substring(6)));
                    push(stack, active, cond);
                    continue;
                } else if (directive.startsWith("if")) {
                    boolean cond = active && evalExpr(directive.substring(2), macros);
                    push(stack, active, cond);
                    continue;
                } else if (directive.startsWith("elif")) {
                    Frame f = stack.peek();
                    if (f != null) {
                        boolean cond = f.parentActive && !f.anyTaken && evalExpr(directive.substring(4), macros);
                        f.active = cond;
                        f.anyTaken |= cond;
                    }
                    continue;
                } else if (directive.startsWith("else")) {
                    Frame f = stack.peek();
                    if (f != null) {
                        f.active = f.parentActive && !f.anyTaken;
                        f.anyTaken = true;
                    }
                    continue;
                } else if (directive.startsWith("endif")) {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    continue;
                } else if (active && directive.startsWith("define")) {
                    defineMacro(directive.substring(6).trim(), macros);
                    // fall through: keep the #define line in the output too
                } else if (active && directive.startsWith("undef")) {
                    macros.remove(firstToken(directive.substring(5)));
                }
            }

            if (active) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static void push(Deque<Frame> stack, boolean parentActive, boolean cond) {
        Frame f = new Frame();
        f.parentActive = parentActive;
        f.active = cond;
        f.anyTaken = cond;
        stack.push(f);
    }

    private static void defineMacro(String rest, Map<String, String> macros) {
        if (rest.isEmpty()) {
            return;
        }
        int i = 0;
        while (i < rest.length() && (Character.isLetterOrDigit(rest.charAt(i)) || rest.charAt(i) == '_')) {
            i++;
        }
        String name = rest.substring(0, i);
        if (i < rest.length() && rest.charAt(i) == '(') {
            return; // function-like macro — ignore
        }
        String value = rest.substring(i);
        // Strip trailing comments from the value — packs annotate option defines like `#define X 1 //[-1 1]`, and the
        // `//[..]` would otherwise leak into the token stream (X → "1 //[-1 1]") and make X evaluate to 0 in an #if.
        int lc = value.indexOf("//");
        if (lc >= 0) {
            value = value.substring(0, lc);
        }
        value = value.replaceAll("/\\*.*?\\*/", " ").trim();
        macros.put(name, value); // valueless → "" (treated as defined)
    }

    private static String firstToken(String s) {
        s = s.trim();
        int i = 0;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        return s.substring(0, i);
    }

    // --- #if expression evaluation ---------------------------------------------------------------------------------

    private static boolean evalExpr(String expr, Map<String, String> macros) {
        try {
            // Strip line comments/block comments that can trail a directive.
            int c = expr.indexOf("//");
            if (c >= 0) {
                expr = expr.substring(0, c);
            }
            expr = expr.replaceAll("/\\*.*?\\*/", " ");
            return new ExprParser(expr, macros).parse() != 0;
        } catch (RuntimeException e) {
            return false; // malformed guard → treat as inactive (safe: we won't over-attach)
        }
    }

    /** Recursive-descent evaluator over the substituted token stream. */
    private static final class ExprParser {
        private final java.util.List<String> tokens;
        private int pos;

        ExprParser(String expr, Map<String, String> macros) {
            this.tokens = tokenize(expr, macros, 0);
        }

        long parse() {
            long v = parseOr();
            return v;
        }

        private String peek() {
            return this.pos < this.tokens.size() ? this.tokens.get(this.pos) : null;
        }

        private boolean eat(String t) {
            if (t.equals(peek())) {
                this.pos++;
                return true;
            }
            return false;
        }

        private long parseOr() {
            long v = parseAnd();
            while (eat("||")) {
                long r = parseAnd();
                v = (v != 0 || r != 0) ? 1 : 0;
            }
            return v;
        }

        private long parseAnd() {
            long v = parseBitOr();
            while (eat("&&")) {
                long r = parseBitOr();
                v = (v != 0 && r != 0) ? 1 : 0;
            }
            return v;
        }

        private long parseBitOr() {
            long v = parseBitXor();
            while (eat("|")) {
                v |= parseBitXor();
            }
            return v;
        }

        private long parseBitXor() {
            long v = parseBitAnd();
            while (eat("^")) {
                v ^= parseBitAnd();
            }
            return v;
        }

        private long parseBitAnd() {
            long v = parseEquality();
            while (eat("&")) {
                v &= parseEquality();
            }
            return v;
        }

        private long parseEquality() {
            long v = parseRelational();
            while (true) {
                if (eat("==")) {
                    v = (v == parseRelational()) ? 1 : 0;
                } else if (eat("!=")) {
                    v = (v != parseRelational()) ? 1 : 0;
                } else {
                    return v;
                }
            }
        }

        private long parseRelational() {
            long v = parseShift();
            while (true) {
                if (eat("<=")) {
                    v = (v <= parseShift()) ? 1 : 0;
                } else if (eat(">=")) {
                    v = (v >= parseShift()) ? 1 : 0;
                } else if (eat("<")) {
                    v = (v < parseShift()) ? 1 : 0;
                } else if (eat(">")) {
                    v = (v > parseShift()) ? 1 : 0;
                } else {
                    return v;
                }
            }
        }

        private long parseShift() {
            long v = parseAdd();
            while (true) {
                if (eat("<<")) {
                    v <<= parseAdd();
                } else if (eat(">>")) {
                    v >>= parseAdd();
                } else {
                    return v;
                }
            }
        }

        private long parseAdd() {
            long v = parseMul();
            while (true) {
                if (eat("+")) {
                    v += parseMul();
                } else if (eat("-")) {
                    v -= parseMul();
                } else {
                    return v;
                }
            }
        }

        private long parseMul() {
            long v = parseUnary();
            while (true) {
                if (eat("*")) {
                    v *= parseUnary();
                } else if (eat("/")) {
                    long r = parseUnary();
                    v = r == 0 ? 0 : v / r;
                } else if (eat("%")) {
                    long r = parseUnary();
                    v = r == 0 ? 0 : v % r;
                } else {
                    return v;
                }
            }
        }

        private long parseUnary() {
            if (eat("!")) {
                return parseUnary() == 0 ? 1 : 0;
            }
            if (eat("~")) {
                return ~parseUnary();
            }
            if (eat("-")) {
                return -parseUnary();
            }
            if (eat("+")) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private long parsePrimary() {
            String t = peek();
            if (t == null) {
                return 0;
            }
            if (eat("(")) {
                long v = parseOr();
                eat(")");
                return v;
            }
            this.pos++;
            try {
                return Long.decode(t);
            } catch (NumberFormatException e) {
                return 0; // stray identifier that survived substitution → undefined → 0
            }
        }
    }

    /** Tokenizes an #if expression, substituting macros (recursively) and resolving {@code defined X}/{@code defined(X)}. */
    private static java.util.List<String> tokenize(String expr, Map<String, String> macros, int depth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0, n = expr.length();
        while (i < n) {
            char ch = expr.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (Character.isLetter(ch) || ch == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(expr.charAt(j)) || expr.charAt(j) == '_')) {
                    j++;
                }
                String word = expr.substring(i, j);
                i = j;
                if (word.equals("defined")) {
                    // defined X  or  defined(X)
                    while (i < n && Character.isWhitespace(expr.charAt(i))) i++;
                    boolean paren = i < n && expr.charAt(i) == '(';
                    if (paren) i++;
                    while (i < n && Character.isWhitespace(expr.charAt(i))) i++;
                    int k = i;
                    while (k < n && (Character.isLetterOrDigit(expr.charAt(k)) || expr.charAt(k) == '_')) k++;
                    String name = expr.substring(i, k);
                    i = k;
                    if (paren) {
                        while (i < n && expr.charAt(i) != ')') i++;
                        if (i < n) i++;
                    }
                    out.add(macros.containsKey(name) ? "1" : "0");
                } else if (macros.containsKey(word) && depth < 32) {
                    String val = macros.get(word);
                    if (val.isEmpty()) {
                        out.add("1"); // valueless define used in an expression → treat as true
                    } else {
                        out.addAll(tokenize(val, macros, depth + 1)); // substitute + recurse
                    }
                } else {
                    out.add(word); // unknown identifier → parsePrimary maps to 0
                }
            } else if (Character.isDigit(ch)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(expr.charAt(j)) || expr.charAt(j) == '.'
                        || expr.charAt(j) == 'x' || expr.charAt(j) == 'X')) {
                    j++;
                }
                out.add(expr.substring(i, j));
                i = j;
            } else {
                // multi-char operators first
                String two = i + 1 < n ? expr.substring(i, i + 2) : "";
                if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=")
                        || two.equals("<=") || two.equals(">=") || two.equals("<<") || two.equals(">>")) {
                    out.add(two);
                    i += 2;
                } else {
                    out.add(String.valueOf(ch));
                    i++;
                }
            }
        }
        return out;
    }
}
