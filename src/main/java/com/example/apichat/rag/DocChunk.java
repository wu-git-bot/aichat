package com.example.apichat.rag;

public class DocChunk {
    private String id;
    private String content;
    private float[] embedding;

    public DocChunk(String id,String content,float[] embedding) {
        this.id = id;
        this. content = content;
        this.embedding = embedding;
    }

    public String getId(){ return id; }
    public String getContent(){return content;}
    public float[] getEmbedding(){return embedding;}

}
