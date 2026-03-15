package com.example.mti.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

    private final MetaDto meta;
    private final T data;

    public ApiResponse(MetaDto meta, T data) {
        this.meta = meta;
        this.data = data;
    }

    public MetaDto getMeta() { return meta; }
    public T getData() { return data; }
}
