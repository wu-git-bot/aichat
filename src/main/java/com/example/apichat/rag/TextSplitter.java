package com.example.apichat.rag;

import java.util.ArrayList;
import java.util.List;

public class TextSplitter {

    public static List<String> split(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }
}