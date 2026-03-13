package com.polestar.mti.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.polestar.mti.constant.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ErrorDataDto {

    @JsonProperty("error_code")
    private String errorCode;

    private String title;

    private String message;

    public static ErrorDataDto from(ErrorCode errorCode, String message) {
        return new ErrorDataDto(errorCode.getCode(), errorCode.getTitle(), message);
    }
}
