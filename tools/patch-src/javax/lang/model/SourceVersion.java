package javax.lang.model;

/** Заглушка для Android: ecj проверяет только наличие констант (RELEASE_12 и выше здесь намеренно нет). */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8;

    public static SourceVersion latest() { return RELEASE_8; }
    public static SourceVersion latestSupported() { return RELEASE_8; }
    public static boolean isIdentifier(CharSequence name) {
        String s = name.toString();
        if (s.length() == 0 || !Character.isJavaIdentifierStart(s.codePointAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.codePointAt(i))) return false;
        return true;
    }
    public static boolean isName(CharSequence name) {
        for (String p : name.toString().split("\\.", -1)) if (!isIdentifier(p) || isKeyword(p)) return false;
        return true;
    }
    public static boolean isKeyword(CharSequence s) {
        switch (s.toString()) {
            case "abstract": case "assert": case "boolean": case "break": case "byte": case "case": case "catch": case "char": case "class": case "const":
            case "continue": case "default": case "do": case "double": case "else": case "enum": case "extends": case "final": case "finally": case "float":
            case "for": case "goto": case "if": case "implements": case "import": case "instanceof": case "int": case "interface": case "long": case "native":
            case "new": case "package": case "private": case "protected": case "public": case "return": case "short": case "static": case "strictfp":
            case "super": case "switch": case "synchronized": case "this": case "throw": case "throws": case "transient": case "try": case "void":
            case "volatile": case "while": case "true": case "false": case "null": case "_": return true;
            default: return false;
        }
    }
}
