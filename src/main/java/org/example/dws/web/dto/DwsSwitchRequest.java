package org.example.dws.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

@Schema(description = "profile 切换请求")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DwsSwitchRequest {

    @Schema(description = "dws profile selector（corpId:userId / corpId / corpName:userName）",
            example = "ding1234567:user_abc",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @Pattern(regexp = "^[\\w:.\\-@/]{1,128}$",
            message = "selector 包含非法字符")
    public String selector;

    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }
}
