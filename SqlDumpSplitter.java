import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SqlDumpSplitter {

    // Target size: 500 MB
    private static final long MAX_FILE_SIZE = 500L * 1024L * 1024L;

    private static int outputNumber = 1;
    private static BufferedWriter writer;
    private static long currentFileSize = 0;

    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.out.println("Usage: java SqlDumpSplitter "+args.toString());
            return;
        }

        Path input = Paths.get(args[0]);

        if (!Files.exists(input)) {
            System.out.println("File not found: " + input);
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {

            openNewFile();

            StringBuilder statement = new StringBuilder();

            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            boolean inBacktick = false;
            boolean inLineComment = false;
            boolean inBlockComment = false;

            int previousChar = -1;

            int c;

            while ((c = reader.read()) != -1) {

                char ch = (char) c;
                statement.append(ch);

                //Handle line comments
                if (inLineComment) {
                    if (ch == '\n') {
                        inLineComment = false;
                    }
                    previousChar = ch;
                    continue;
                }

                //Handle block comments
                if (inBlockComment) {
                    if (previousChar == '*' && ch == '/') {
                        inBlockComment = false;
                    }
                    previousChar = ch;
                    continue;
                }

                // Handle quotes
                if (inSingleQuote) {
                    if (ch == '\'' && previousChar != '\\') {
                        inSingleQuote = false;
                    }
                    previousChar = ch;
                    continue;
                }

                if (inDoubleQuote) {
                    if (ch == '"' && previousChar != '\\') {
                        inDoubleQuote = false;
                    }
                    previousChar = ch;
                    continue;
                }

                if (inBacktick) {
                    if (ch == '`') {
                        inBacktick = false;
                    }
                    previousChar = ch;
                    continue;
                }

                /*
                 * Detect comments
                 */
                if (previousChar == '-' && ch == '-') {
                    inLineComment = true;
                }

                if (previousChar == '/' && ch == '*') {
                    inBlockComment = true;
                }

                // Detect quotes
                if (ch == '\'') {
                    inSingleQuote = true;
                }

                if (ch == '"') {
                    inDoubleQuote = true;
                }

                if (ch == '`') {
                    inBacktick = true;
                }

                // A semicolon outside strings/comments means the SQL statement is complete.
                if (ch == ';' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment && !inBlockComment) {

                    processStatement(statement.toString());

                    statement.setLength(0);
                }

                previousChar = ch;
            }

            // Handle anything left at EOF
            if (statement.length() > 0) {
                processStatement(statement.toString());
            }
        }

        closeCurrentFile();

        System.out.println();
        System.out.println("Finished.");
        System.out.println("Created " + (outputNumber - 1) + " files.");
    }

    //Process one complete SQL statement.
    private static void processStatement(String statement) throws IOException {
        if (statement.isBlank()) {
            return;
        }
        //If this statement fits, simply write it.
        byte[] bytes = statement.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_FILE_SIZE - currentFileSize) {
            write(statement);
            return;
        }

        /* The statement doesn't fit in the current file.
         * Try to split a large INSERT between rows.*/
        if (isInsertStatement(statement)) {

            List<String> parts = splitInsertStatement(statement);

            for (String part : parts) {

                byte[] partBytes = part.getBytes(StandardCharsets.UTF_8);

                if (partBytes.length > MAX_FILE_SIZE) {

                    throw new IOException("A single INSERT row is larger than 500 MB. " + "Cannot safely split it: "
                            + partBytes.length + " bytes");
                }

                if (partBytes.length > MAX_FILE_SIZE - currentFileSize) {
                    startNewFile();
                }

                write(part);
            }

        } else {

            /* CREATE TABLE, SET, DROP, etc.
             * Never split the statement.*/
            startNewFile();
            write(statement);
        }
    }

    /* Determines whether the statement is an INSERT.*/
    private static boolean isInsertStatement(String statement) {

        String trimmed = statement.trim().toUpperCase();

        return trimmed.startsWith("INSERT ");
    }

    /*
     * Split a multi-row INSERT into separate INSERT statements.
     * Example:
     * INSERT INTO users VALUES (1,'John'), (2,'Mary'), (3,'Bob');
     *
     * becomes:
     * INSERT INTO users VALUES (1,'John'); INSERT INTO users VALUES (2,'Mary');
     * INSERT INTO users VALUES (3,'Bob');
     */
    private static List<String> splitInsertStatement(String sql) {
        List<String> result = new ArrayList<>();
        int valuesIndex = findValuesKeyword(sql);
        if (valuesIndex == -1) {
            result.add(sql);
            return result;
        }
        int valuesStart = valuesIndex + 6;
        String prefix = sql.substring(0, valuesStart);

        /*
         * Remove the final semicolon.
         */
        String remainder = sql.substring(valuesStart).trim();

        if (remainder.endsWith(";")) {
            remainder = remainder.substring(0, remainder.length() - 1);
        }

        /*
         * Extract individual (...) rows.
         */
        List<String> rows = extractRows(remainder);

        if (rows.isEmpty()) {
            result.add(sql);
            return result;
        }

        StringBuilder current = new StringBuilder();

        for (String row : rows) {

            String candidate;

            if (current.length() == 0) {

                candidate = prefix + " " + row + ";";

            } else {

                candidate = prefix + " " + current + ",\n" + row + ";";
            }
            byte[] candidateBytes = candidate.getBytes(StandardCharsets.UTF_8);
            /*If adding this row would exceed 500 MB, finish the current INSERT first.
             */
            if (candidateBytes.length > MAX_FILE_SIZE && current.length() > 0) {
                result.add(prefix + " " + current + ";");
                current.setLength(0);
                candidate = prefix + " " + row + ";";
            }
            current.append(current.length() == 0 ? row : ",\n" + row);
        }

        if (current.length() > 0) {
            result.add(prefix + " " + current + ";");
        }

        return result;
    }

    /* Find the VALUES keyword while ignoring strings.*/
    private static int findValuesKeyword(String sql) {

        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        for (int i = 0; i <= sql.length() - 6; i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDouble && !inBacktick) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle && !inBacktick) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '`' && !inSingle && !inDouble) {
                inBacktick = !inBacktick;
                continue;
            }
            if (!inSingle && !inDouble && !inBacktick) {
                String part = sql.substring(i, i + 6);
                if (part.equalsIgnoreCase("VALUES")) {
                    /* Make sure VALUES is a word, not part of another identifier.*/
                    boolean beforeOk = i == 0 || !Character.isLetterOrDigit(sql.charAt(i - 1));
                    boolean afterOk = i + 6 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(i + 6));
                    if (beforeOk && afterOk) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /*
     * Extract (...) rows from the VALUES section.
     * Parentheses inside quoted strings are ignored.
     */
    private static List<String> extractRows(String values) {

        List<String> rows = new ArrayList<>();

        boolean inSingle = false;
        boolean inDouble = false;

        int depth = 0;
        int rowStart = -1;

        char previous = 0;

        for (int i = 0; i < values.length(); i++) {
            char c = values.charAt(i);
            if (c == '\'' && !inDouble && previous != '\\') {
                inSingle = !inSingle;
            }
            if (c == '"' && !inSingle && previous != '\\') {
                inDouble = !inDouble;
            }
            if (!inSingle && !inDouble) {
                if (c == '(') {
                    if (depth == 0) {
                        rowStart = i;
                    }
                    depth++;
                }
                else if (c == ')') {
                    depth--;
                    if (depth == 0 && rowStart >= 0) {
                        rows.add(values.substring(rowStart, i + 1).trim());
                        rowStart = -1;
                    }
                }
            }

            previous = c;
        }

        return rows;
    }

    private static void openNewFile() throws IOException {
        String filename = "dump" + outputNumber + ".sql";
        writer = Files.newBufferedWriter(Paths.get(filename), StandardCharsets.UTF_8);
        currentFileSize = 0;
        System.out.println("Creating " + filename);
        outputNumber++;
    }

    private static void startNewFile() throws IOException {
        closeCurrentFile();
        openNewFile();
    }

    private static void write(String text) throws IOException {

        writer.write(text);

        currentFileSize += text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void closeCurrentFile() throws IOException {

        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}