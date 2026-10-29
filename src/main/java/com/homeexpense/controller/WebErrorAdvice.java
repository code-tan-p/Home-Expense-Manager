package com.homeexpense.controller;

import java.net.URI;
import java.time.format.DateTimeParseException;

import com.homeexpense.exception.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Shows rule violations and unreadable input as a plain message on the page the user came from. */
@ControllerAdvice
public class WebErrorAdvice {

    @ExceptionHandler(BusinessRuleException.class)
    public String businessRule(BusinessRuleException e, HttpServletRequest request, RedirectAttributes flash) {
        return back(e.getMessage(), request, flash);
    }

    @ExceptionHandler({DateTimeParseException.class, NumberFormatException.class, ArithmeticException.class,
            MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public String badInput(Exception e, HttpServletRequest request, RedirectAttributes flash) {
        return back("Please check what you typed and try again.", request, flash);
    }

    private static String back(String message, HttpServletRequest request, RedirectAttributes flash) {
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
