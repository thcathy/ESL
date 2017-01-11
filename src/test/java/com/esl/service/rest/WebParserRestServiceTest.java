package com.esl.service.rest;

import com.esl.entity.rest.WebItem;
import com.mashape.unirest.http.HttpResponse;
import org.assertj.core.util.Strings;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertFalse;

public class WebParserRestServiceTest {
    private Logger log = LoggerFactory.getLogger(WebParserRestServiceTest.class);

    private static WebParserRestService service;

    @BeforeClass
    public static void setup() {
        String host = System.getenv("APISERVER_HOST");
        if (com.google.common.base.Strings.isNullOrEmpty(host)) host = System.getProperty("APISERVER_HOST");
        if (com.google.common.base.Strings.isNullOrEmpty(host)) host = "funfunspell.com:8091";
        service = new WebParserRestService(host);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createServiceWithoutHost_ShouldThrowException() {
        new WebParserRestService(null);
    }

    @Test
    public void searchGoogleImage_shouldReturn10WebItem() throws ExecutionException, InterruptedException {
        Future<HttpResponse<WebItem[]>> result = service.searchGoogleImage("testing");
        WebItem[] items = result.get().getBody();

        assert items.length == 10;
        for (WebItem item : items) {
            assertFalse(Strings.isNullOrEmpty(item.url));
        }
    }
}