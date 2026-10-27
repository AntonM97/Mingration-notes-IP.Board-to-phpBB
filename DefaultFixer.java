package com.scriptprocessing;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class DefaultFixer {

    public static void main(String[] args) throws FileNotFoundException, IOException {
        File input = new File("/home/anton/ForumMigration/DumpToConvert2026-08-12/shkoladuraka3.sql");
        File output = new File("/home/anton/ForumMigration/Forum1/forum1.hiknomore.cc/dump/backup3.sql");

        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(input), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.replace("DEFAULT '0000-00-00 00:00:00'", "DEFAULT CURRENT_TIMESTAMP");
                line = line.replace("'0000-00-00'", "'1900-01-01'");
                writer.write(line);
                writer.newLine();
            }
        }
        System.out.println("Done.");
    }
    
    
    /*If you need to DELETE some rows

    while ((line = reader.readLine()) != null) {
        if (!line.contains("DELETE FROM")) {
            writer.write(line);
            writer.newLine();
        }
    }*/
    
    /*
     * if (line.contains("user_id = 12345")) {
    line = line.replace("old@email.com", "new@email.com");
}
     * */

}
