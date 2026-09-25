package com.github.xingjianluo.resilientidempotentgatewayjava.web;

import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandRequest;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandResponse;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandResult;
import com.github.xingjianluo.resilientidempotentgatewayjava.command.CommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 命令入口：同一 (callerId, idempotencyKey) 的副作用只发生一次。 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService service;

    public CommandController(CommandService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CommandResponse> execute(
            @RequestHeader(value = "X-Caller-Id", required = false) String callerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody(required = false) CommandRequest request) {
        CommandResult result = service.execute(callerId, idempotencyKey, request, traceId);
        return ResponseEntity.status(result.httpStatus())
                .body(CommandResponse.from(result, idempotencyKey));
    }
}
