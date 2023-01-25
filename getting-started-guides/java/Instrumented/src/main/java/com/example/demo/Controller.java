package com.example.demo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

@RestController
public class Controller {

  // Logger (note that this is not an OTel component)
  private static final Logger LOGGER = LogManager.getLogger(Controller.class);
  
  // Attribute constants
  private static final AttributeKey<Long> ATTR_N = AttributeKey.longKey("fibonacci.n");
  private static final AttributeKey<Long> ATTR_RESULT = AttributeKey.longKey("fibonacci.result");
  private static final AttributeKey<Boolean> ATTR_VALID_N =
      AttributeKey.booleanKey("fibonacci.valid.n");

  private final Tracer tracer;
  private final LongCounter fibonacciInvocations;

  @Autowired
  Controller(OpenTelemetry openTelemetry) {
    // Initialize tracer
    tracer = openTelemetry.getTracer(Controller.class.getName());
    // Initialize instrument
    Meter meter = openTelemetry.getMeter(Controller.class.getName());
    fibonacciInvocations =
        meter
            .counterBuilder("fibonacci.invocations")
            .setDescription("Measures the number of times the fibonacci method is invoked.")
            .build();
  }

  @GetMapping(value = "/fibonacci")
  public Map<String, Object> getFibonacci(@RequestParam(required = true, name = "n") long n) {
    return Map.of("n", n, "result", fibonacci(n));
  }

  /**
   * Compute the fibonacci number for {@code n}.
   *
   * @param n must be >=1 and <= 90.
   */
  private long fibonacci(long n) {
    // Start a new span and set your first attribute
    var span = tracer.spanBuilder("fibonacci").setAttribute(ATTR_N, n).startSpan();
    
    // Set the span as the current span 
    try (var scope = span.makeCurrent()) {
      if (n < 1 || n > 90) {
        throw new IllegalArgumentException("n must be 1 <= n <= 90.");
      }

      long result = 1;
      if (n > 2) {
        long a = 0;
        long b = 1;

        for (long i = 1; i < n; i++) {
          result = a + b;
          a = b;
          b = result;
        }
      }
      // Set a span attribute to capture information about successful requests
      span.setAttribute(ATTR_RESULT, result);
      // Counter to increment when a valid input is recorded
      fibonacciInvocations.add(1, Attributes.of(ATTR_VALID_N, true));
      // Log the result of a valid input
      LOGGER.info("Compute fibonacci(" + n + ") = " + result);
      return result;
    } catch (IllegalArgumentException e) {
      // Record the exception and set the span status
      span.recordException(e).setStatus(StatusCode.ERROR, e.getMessage());
      // Counter to increment when an invalid input is recorded
      fibonacciInvocations.add(1, Attributes.of(ATTR_VALID_N, false));
      // Log when no output was recorded
      LOGGER.info("Failed to compute fibonacci(" + n + ")");
      throw e;
    } finally {
      // End the span
      span.end();
    }
  }

  @ControllerAdvice
  private static class ErrorHandler {

    @ExceptionHandler({
      IllegalArgumentException.class,
      MissingServletRequestParameterException.class,
      HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<Object> handleException(Exception e) {
      // Set the span status and description
      Span.current().setStatus(StatusCode.ERROR, e.getMessage());
      return new ResponseEntity<>(Map.of("message", e.getMessage()), HttpStatus.BAD_REQUEST);
    }
  }
}
