package com.sub.kra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sub.kra.client.KraApiException;
import com.sub.kra.client.ResponseParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponseParserTest {
    ResponseParser p = new ResponseParser(new ObjectMapper());

    @Test void array() {
        ResponseParser.Page pg = p.parse("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[{\"hrName\":\"A\"},{\"hrName\":\"B\"}]},\"totalCount\":2}}}");
        assertEquals(2, pg.items.size()); assertEquals("B", pg.items.get(1).get("hrName").asText());
    }
    @Test void singleObject() {
        ResponseParser.Page pg = p.parse("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"hrName\":\"A\"}},\"totalCount\":1}}}");
        assertEquals(1, pg.items.size());
    }
    @Test void emptyItems() {
        ResponseParser.Page pg = p.parse("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":\"\",\"totalCount\":0}}}");
        assertTrue(pg.items.isEmpty());
    }
    @Test void noData03() {
        assertTrue(p.parse("{\"response\":{\"header\":{\"resultCode\":\"03\",\"resultMsg\":\"NODATA_ERROR\"}}}").items.isEmpty());
    }
    @Test void xmlKeyError() {
        KraApiException e = assertThrows(KraApiException.class, () -> p.parse(
            "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"));
        assertEquals("30", e.getCode()); assertTrue(e.isFatal());
    }
    @Test void dailyLimit22() {
        KraApiException e = assertThrows(KraApiException.class, () -> p.parse(
            "{\"response\":{\"header\":{\"resultCode\":\"22\",\"resultMsg\":\"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR\"}}}"));
        assertTrue(e.isFatal());
    }
}
