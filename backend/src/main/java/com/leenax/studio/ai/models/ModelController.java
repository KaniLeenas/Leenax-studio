package com.leenax.studio.ai.models;

import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
public class ModelController {

    private final AiModelRepository models;

    public ModelController(AiModelRepository models) {
        this.models = models;
    }

    public record ProviderInfo(ProviderCode code, String name, List<String> capabilities, boolean balanceApi,
                               String keyUrl, String keyHelp) {
    }

    public record ModelResponse(UUID id, ProviderCode provider, String modelCode, String displayName,
                                AiModel.Capability capability, Map<String, Object> options,
                                Map<String, Object> pricing, String notes, boolean enabled) {
        static ModelResponse of(AiModel m) {
            return new ModelResponse(m.getId(), m.getProvider(), m.getModelCode(), m.getDisplayName(), m.getCapability(),
                    m.getOptions(), m.getPricing(), m.getNotes(), m.isEnabled());
        }
    }

    /** Partial update. Pricing example: {"unit":"CREDITS","rates":[{"perSecond":12}]}. */
    public record UpdateModelRequest(@Size(min = 1, max = 160) String displayName, Boolean enabled,
                                     Map<String, Object> pricing, @Size(max = 2000) String notes) {
    }

    @GetMapping("/providers")
    public List<ProviderInfo> providers() {
        return Arrays.stream(ProviderCode.values())
                .map(p -> new ProviderInfo(p, p.displayName(),
                        p.capabilities().stream().map(Enum::name).sorted().toList(),
                        p.hasBalanceApi(), p.keyUrl(), p.keyHelp()))
                .toList();
    }

    @GetMapping("/models")
    @Transactional(readOnly = true)
    public List<ModelResponse> list() {
        return models.findAllByOrderBySortOrderAscDisplayNameAsc().stream().map(ModelResponse::of).toList();
    }

    @PatchMapping("/models/{id}")
    @Transactional
    public ModelResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateModelRequest req) {
        AiModel m = models.findById(id).orElseThrow(() -> ApiException.notFound("Model"));
        if (req.displayName() != null) m.setDisplayName(req.displayName().trim());
        if (req.enabled() != null) m.setEnabled(req.enabled());
        if (req.notes() != null) m.setNotes(req.notes().isBlank() ? null : req.notes().trim());
        if (req.pricing() != null) m.setPricing(PricingValidator.validate(req.pricing()));
        return ModelResponse.of(m);
    }
}
