import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

public class MySqlDumpSplitter {

    // 500 MiB
    private static final long MAX_FILE_SIZE = 500L * 1024L * 1024L;

    private static int outputNumber = 1;

    private static BufferedWriter outputWriter;
    private static long outputSize = 0;

    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.out.println("Usage:");
            System.out.println("java SqlDumpSplitter " + args[0].toString());
            return;
        }

        Path inputFile = Paths.get(args[0]);

        if (!Files.exists(inputFile)) {
            throw new FileNotFoundException("Input file not found: " + inputFile);
        }
        /* Temporary directory for individual SQL statements.
         * This prevents giant INSERT statements from being held in RAM.*/
        Path tempDir = Files.createTempDirectory("sql-dump-splitter-");

        System.out.println("Input : " + inputFile);
        System.out.println("Temp  : " + tempDir);
        System.out.println();

        try {
            openOutputFile();
            readStatements(inputFile, tempDir);
        } finally {
            closeOutputFile();
            deleteDirectory(tempDir);/*Remove temporary files.*/
        }
        System.out.println();
        System.out.println("Finished.");
        System.out.println("Created " + (outputNumber - 1) + " files.");
    }

    /*Read the dump and identify complete SQL statements.
     * Statements are terminated by ';' when we are not inside a quoted string or
     * comment. */
    private static void readStatements(Path inputFile, Path tempDir) throws Exception {

        Path statementFile = null;
        OutputStream statementOut = null;

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        boolean inLineComment = false;
        boolean inBlockComment = false;

        int previous = -1;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(inputFile), 1024 * 1024)) {
            int c;
            while ((c = in.read()) != -1) {

                /* Create temporary statement file lazily. */
                if (statementOut == null) {
                    statementFile = Files.createTempFile(tempDir, "statement-", ".sql");
                    statementOut = new BufferedOutputStream(Files.newOutputStream(statementFile), 1024 * 1024);
                }

                statementOut.write(c);

                char ch = (char) c;
                if (inLineComment) {
                    if (ch == '\n') {
                        inLineComment = false;
                    }
                    previous = c;
                    continue;
                }
                if (inBlockComment) {
                    if (previous == '*' && ch == '/') {
                        inBlockComment = false;
                    }
                    previous = c;
                    continue;
                }
                if (inSingleQuote) {
                    if (ch == '\'' && previous != '\\') {
                        inSingleQuote = false;
                    }
                    previous = c;
                    continue;
                }
                if (inDoubleQuote) {
                    if (ch == '"' && previous != '\\') {
                        inDoubleQuote = false;
                    }
                    previous = c;
                    continue;
                }
                if (inBacktick) {/* Backtick identifier */
                    if (ch == '`') {
                        inBacktick = false;
                    }
                    previous = c;
                    continue;
                }
                /*Detect comments */
                if (previous == '-' && ch == '-') {
                    inLineComment = true;
                }
                if (previous == '#') {
                    inLineComment = true;
                }
                if (previous == '/' && ch == '*') {
                    inBlockComment = true;
                }
                /* Detect quoted strings */
                if (ch == '\'') {
                    inSingleQuote = true;
                }
                if (ch == '"') {
                    inDoubleQuote = true;
                }
                if (ch == '`') {
                    inBacktick = true;
                }
                /* End of SQL statement.*/
                if (ch == ';' && !inSingleQuote && !inDoubleQuote && !inBacktick && !inLineComment && !inBlockComment) {

                    statementOut.flush();
                    statementOut.close();
                    statementOut = null;

                    processStatement(statementFile);

                    Files.deleteIfExists(statementFile);
                    statementFile = null;
                }
                previous = c;
            }
        }

        /* Process anything remaining at EOF.*/
        if (statementOut != null) {
            statementOut.flush();
            statementOut.close();
            processStatement(statementFile);
            Files.deleteIfExists(statementFile);
        }
    }

    /*Process one complete SQL statement.*/
    private static void processStatement(Path statementFile) throws Exception {
        if (Files.size(statementFile) == 0) {
            return;
        }
        /*Read only the beginning of the statement to determine whether it is an INSERT.
         */
        String beginning = readBeginning(statementFile, 8192).trim().toUpperCase(Locale.ROOT);
        if (beginning.startsWith("INSERT ") || beginning.startsWith("INSERT\n") || beginning.startsWith("INSERT\t")) {
            splitInsert(statementFile);
        } else {
            /* CREATE TABLE and all other statements stay intact.*/
            writeWholeStatement(statementFile);
        }
    }

    /* Read a small portion of the statement.*/
    private static String readBeginning(Path file, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(maxBytes);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] data = new byte[8192];
            int remaining = maxBytes;
            int read;
            while (remaining > 0 && (read = in.read(data, 0, Math.min(data.length, remaining))) != -1) {
                buffer.write(data, 0, read);
                remaining -= read;
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /* Write a normal SQL statement.
     * CREATE TABLE statements are handled here and are never split. */
    private static void writeWholeStatement(Path statementFile) throws IOException {
        long size = Files.size(statementFile);
        if (size > MAX_FILE_SIZE) {
            /* A CREATE TABLE should normally be tiny compared with 500 MB. If something
             * else enormous gets here, don't silently corrupt it. */
            if (outputSize > 0) {
                startNewOutputFile();
            }
            System.out.println("WARNING: statement is larger than 500 MB: " + formatSize(size));
        }
        if (size > MAX_FILE_SIZE - outputSize && outputSize > 0) {
            startNewOutputFile();
        }
        copyFileToOutput(statementFile);
    }

    /*Split a huge INSERT between complete VALUES rows. */
    private static void splitInsert(Path statementFile) throws Exception {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(statementFile), 1024 * 1024)) {
            /*First find the VALUES keyword and preserve everything before it as the INSERT
             * prefix. */
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            boolean foundValues = readUntilValues(in, prefix);
            if (!foundValues) {
                /* Not a format we recognize safely. */
                writeWholeStatement(statementFile);
                return;
            }
            byte[] prefixBytes = prefix.toByteArray();
            /* Now read individual (...) rows. */
            ByteArrayOutputStream row = new ByteArrayOutputStream();

            boolean inSingle = false;
            boolean inDouble = false;

            int depth = 0;
            boolean startedRow = false;
            int previous = -1;

            int c;

            while ((c = in.read()) != -1) {
                char ch = (char) c;
                /* We only care about quotes and parentheses here.*/
                if (inSingle) {
                    row.write(c);
                    if (ch == '\'' && previous != '\\') {
                        inSingle = false;
                    }
                    previous = c;
                    continue;
                }
                if (inDouble) {
                    row.write(c);
                    if (ch == '"' && previous != '\\') {
                        inDouble = false;
                    }
                    previous = c;
                    continue;
                }
                if (ch == '\'') {
                    inSingle = true;
                    row.write(c);
                    previous = c;
                    continue;
                }
                if (ch == '"') {
                    inDouble = true;
                    row.write(c);
                    previous = c;
                    continue;
                }
                if (ch == '(') {
                    depth++;
                    if (depth == 1) {
                        startedRow = true;
                    }
                    row.write(c);
                }
                else if (ch == ')') {
                    depth--;
                    row.write(c);
                    /* A complete VALUES row. */
                    if (depth == 0 && startedRow) {
                        byte[] rowBytes = row.toByteArray();
                        writeInsertRow(prefixBytes, rowBytes);
                        row.reset();
                        startedRow = false;
                    }
                }
                else {
                    /* Commas, whitespace, semicolon, etc.  */
                    if (startedRow) {
                        row.write(c);
                    }
                }
                previous = c;
            }
            /* If there is unexpected remaining data, warn.*/
            if (row.size() > 0) {
                System.out.println("WARNING: leftover data at end of INSERT: " + row.size() + " bytes");
                byte[] remaining = row.toByteArray();
                writeBytes(remaining);
            }
        }
    }

    /* Find:
     * INSERT INTO ... VALUES
     * while respecting quoted strings. */
    private static boolean readUntilValues(InputStream in, ByteArrayOutputStream prefix) throws IOException {
        StringBuilder recent = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        int previous = -1;
        int c;

        while ((c = in.read()) != -1) {

            char ch = (char) c;

            prefix.write(c);

            if (inSingle) {
                if (ch == '\'' && previous != '\\') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (ch == '"' && previous != '\\') {
                    inDouble = false;
                }
            } else if (inBacktick) {
                if (ch == '`') {
                    inBacktick = false;
                }
            } else {
                if (ch == '\'') {
                    inSingle = true;
                }
                else if (ch == '"') {
                    inDouble = true;
                }
                else if (ch == '`') {
                    inBacktick = true;
                }
                else {
                    recent.append(ch);
                    if (recent.length() > 20) {
                        recent.deleteCharAt(0);
                    }
                    String text = recent.toString().toUpperCase(Locale.ROOT);
                    if (text.matches(".*\\bVALUES\\s*$")) {
                       return true;
                    }
                }
            }
            previous = c;
        }
        return false;
    }

    /*Write one complete INSERT row.
     * If it doesn't fit, start a new output file and begin another INSERT
     * statement.*/
    private static void writeInsertRow(byte[] prefix, byte[] row) throws IOException {
        /*A single row cannot be split. */
        long rowSize = prefix.length + row.length + 3;
        if (rowSize > MAX_FILE_SIZE) {
            throw new IOException("A single INSERT row is larger than 500 MiB: " + formatSize(rowSize));
        }
        /*Determine the size of this row in an INSERT statement.
         * We generate:
         * INSERT ... VALUES (row); */
        long required = prefix.length + row.length + 3;

        if (required > MAX_FILE_SIZE - outputSize) {

            startNewOutputFile();
        }
        /*Write the prefix only when beginning a new INSERT.
         *Since an INSERT may contain many rows, we need to know whether the current
         * output file is already in the middle of one. */
        writeBytes(prefix);
        writeBytes(row);
        writeBytes(new byte[] { ';', '\n' });
    }

    /*Open dump1.sql, dump2.sql, etc.*/
    private static void openOutputFile() throws IOException {
        String filename = "dump" + outputNumber + ".sql";
        outputWriter = Files.newBufferedWriter(Paths.get(filename), StandardCharsets.UTF_8);
        outputSize = 0;
        System.out.println("Creating " + filename);
        outputNumber++;
    }

    private static void startNewOutputFile() throws IOException {
        closeOutputFile();
        openOutputFile();
    }

    private static void closeOutputFile() throws IOException {
        if (outputWriter != null) {
            outputWriter.flush();
            outputWriter.close();
            outputWriter = null;
        }
    }

    /*Copy a file without loading it into memory. */
    private static void copyFileToOutput(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                outputWriter.write(new String(buffer, 0, read, StandardCharsets.UTF_8));
                outputSize += read;
            }
        }
    }

    private static void writeBytes(byte[] data) throws IOException {
        outputWriter.write(new String(data, StandardCharsets.UTF_8));
        outputSize += data.length;
    }

    /* Format bytes for human-readable output. */
    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
        }
        return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
    }

    /* Delete temporary directory and its contents.  */
    private static void deleteDirectory(Path directory) {
        try {
            if (directory == null || !Files.exists(directory)) {
                return;
            }
            Files.walk(directory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            System.err.println("Could not delete: " + path);
                        }
                    });
        } catch (IOException e) {
            System.err.println("Could not clean temporary directory: " + directory);
        }
    }
}
