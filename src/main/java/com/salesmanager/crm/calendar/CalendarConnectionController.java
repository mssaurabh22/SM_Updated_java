package com.salesmanager.crm.calendar;

import com.salesmanager.crm.calendar.dto.CalendarConnectionResponse;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * {@code /{provider}/authorize-url} is a normal authenticated JSON API call (the frontend does
 * {@code window.location.href = response.url} itself); {@code /{provider}/callback} is the one
 * unauthenticated exception in this controller (see SecurityConfig's permitAll for this exact
 * path) - Google/Microsoft redirect the browser here directly after consent, with no
 * Authorization header attached, which is also why it ends in a 302 RedirectView back to the
 * frontend rather than a JSON response nobody would see.
 */
@RestController
@RequestMapping("/calendar-connections")
public class CalendarConnectionController {

    private final CalendarConnectionService calendarConnectionService;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    public CalendarConnectionController(CalendarConnectionService calendarConnectionService) {
        this.calendarConnectionService = calendarConnectionService;
    }

    @GetMapping("/me")
    @RequireEntitlement(FeatureEntitlement.CALENDAR_SYNC)
    public CalendarConnectionResponse me() {
        return calendarConnectionService.getStatus()
                .map(CalendarConnectionResponse::from)
                .orElseGet(CalendarConnectionResponse::notConnected);
    }

    @GetMapping("/{provider}/authorize-url")
    @RequireEntitlement(FeatureEntitlement.CALENDAR_SYNC)
    public AuthorizeUrlResponse authorizeUrl(@PathVariable CalendarProvider provider) {
        return new AuthorizeUrlResponse(calendarConnectionService.generateAuthorizeUrl(provider));
    }

    @GetMapping("/{provider}/callback")
    public RedirectView callback(@PathVariable CalendarProvider provider,
                                  @RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error) {
        boolean success = error == null && code != null && state != null
                && calendarConnectionService.handleCallback(provider, code, state);
        return new RedirectView(frontendBaseUrl + "/app/settings?calendar=" + (success ? "connected" : "error"));
    }

    @DeleteMapping("/me")
    @RequireEntitlement(FeatureEntitlement.CALENDAR_SYNC)
    @ResponseStatus(HttpStatus.OK)
    public void disconnect() {
        calendarConnectionService.disconnect();
    }

    public record AuthorizeUrlResponse(String url) {
    }
}
