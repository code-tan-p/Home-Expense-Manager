package com.homeexpense.controller;

import java.net.URI;
import java.time.DateTimeException;
import java.time.format.DateTimeParseException;

import com.homeexpense.exception.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.ui.Model;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Turns every failure into a plain message.
 *
 * <p>A form post that fails goes back to the page it came from with the message. A page that fails
 * to open (a GET) shows the error page itself and never redirects, so a bad record can never trap
 * the browser in a redirect loop. Anything unexpected is logged and shown as a friendly page, never
 * as a stack trace.
 */
@ControllerAdvice
public class WebErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(WebErrorAdvice.class);

    @ExceptionHandler(BusinessRuleException.class)
    public String businessRule(BusinessRuleException e, HttpServletRequest request, HttpServletResponse response,
                               RedirectAttributes flash, Model model) {
        return problem(e.getMessage(), 400, request, response, flash, model);
    }

    @ExceptionHandler({DateTimeException.class, NumberFormatException.class, ArithmeticException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public String badInput(Exception e, HttpServletRequest request, HttpServletResponse response,
                           RedirectAttributes flash, Model model) {
        String message = e instanceof DateTimeParseException || e instanceof MethodArgumentTypeMismatchException
                ? "Something you typed is not a valid date, number or choice. Please check it and try again."
                : "Please check what you typed and try again.";
        return problem(message, 400, request, response, flash, model);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, HttpServletResponse response, RedirectAttributes flash, Model model) {
        return problem("That file is too large to be a backup of this program.", 413, request, response, flash, model);
    }

    @ExceptionHandler(DataAccessException.class)
    public String database(DataAccessException e, HttpServletRequest request, HttpServletResponse response,
                           RedirectAttributes flash, Model model) {
        log.warn("Database problem on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem("The records could not be saved just now. Please wait a moment and try again. "
                + "Nothing was changed.", 503, request, response, flash, model);
    }

    /** Last resort for anything unexpected. Errors that already carry an HTTP status (404, 405) are left to Spring. */
    @ExceptionHandler(RuntimeException.class)
    public String unexpected(RuntimeException e, HttpServletRequest request, HttpServletResponse response,
                             RedirectAttributes flash, Model model) {
        if (e instanceof ErrorResponse) {
            throw e;
        }
        log.error("Unexpected problem on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem("Something went wrong on our side. Your records are safe. Please try again.", 500,
                request, response, flash, model);
    }

    private static String problem(String message, int status, HttpServletRequest request, HttpServletResponse response,
                                  RedirectAttributes flash, Model model) {
        boolean pageLoad = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        if (pageLoad) {
            response.setStatus(status);
            model.addAttribute("status", status);
            model.addAttribute("message", message);
            model.addAttribute("page", "");
            return "error";
        }
        flash.addFlashAttribute("error", message);
        return "redirect:" + samePagePath(request.getHeader("Referer"));
    }

    /** Only the path and query of the previous page are used, so a forged Referer cannot send the user elsewhere. */
    static String samePagePath(String referer) {
        if (referer == null) {
            return "/";
        }
        try {
            URI uri = URI.create(referer);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return "/";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
