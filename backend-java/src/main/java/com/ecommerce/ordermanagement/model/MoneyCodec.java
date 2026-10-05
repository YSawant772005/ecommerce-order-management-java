package com.ecommerce.ordermanagement.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one definition of what money looks like on the wire.
 *
 * <p>The former FastAPI backend carried every monetary value as {@code Decimal}
 * in Python and as an exact two-place <b>string</b> in JSON ("150.50", never
 * 150.5). Java's {@code BigDecimal} is the equivalent of {@code Decimal}; these
 * serializer/deserializer pair apply the same rule so the frontend keeps
 * receiving strings and the ES projection keeps storing exact cents.</p>
 */
public final class MoneyCodec {

    private MoneyCodec() {
    }

    /** {@code BigDecimal} -> exact two-place JSON string, never a float. */
    public static final class MoneySerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
    }

    /** Accepts a JSON string or number and normalizes to {@code BigDecimal}. */
    public static final class MoneyDeserializer extends JsonDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                return text.isEmpty() ? null : new BigDecimal(text);
            }
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                return p.getDecimalValue();
            }
            return (BigDecimal) ctxt.handleUnexpectedToken(BigDecimal.class, p);
        }
    }
}
