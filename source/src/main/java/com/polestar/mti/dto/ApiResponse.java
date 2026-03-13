package com.polestar.mti.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ApiResponse<T> {

    private MetaDto meta;
    private T data;
}
