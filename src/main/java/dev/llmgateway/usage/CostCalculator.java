package dev.llmgateway.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.llmgateway.api.openai.Usage;
import dev.llmgateway.config.GatewayProperties;
import dev.llmgateway.config.GatewayProperties.Price;

/** Prices a call from token usage. BigDecimal throughout: never use double for money. */
@Component
public class CostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, Price> pricing;

    public CostCalculator(GatewayProperties properties) {
        this.pricing = properties.pricing();
    }

    public BigDecimal cost(String upstreamModel, Usage usage) {
        if (upstreamModel == null || usage == null) {
            return BigDecimal.ZERO;
        }
        Price price = pricing.get(upstreamModel);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal input = price.inputPerMillion().multiply(BigDecimal.valueOf(usage.promptTokens()));
        BigDecimal output = price.outputPerMillion().multiply(BigDecimal.valueOf(usage.completionTokens()));
        return input.add(output).divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }
}
