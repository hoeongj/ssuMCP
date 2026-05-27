package com.ssuai.domain.chat.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.chat.llm")
public class LlmChatProperties {

    private int maxTokens = 400;
    private double temperature = 0.3;
    private int availabilityVerificationPasses = 0;
    private int maxProviderAttempts = 6;
    private int maxModelsPerProvider = 2;
    private int maxToolCalls = 2;
    private List<String> providerOrder = new ArrayList<>(
            List.of("gemini", "groq", "openrouter", "cerebras", "deepinfra", "sambanova", "nscale",
                    "fireworks", "huggingface", "mistral")
    );
    private List<String> privateProviderOrder = new ArrayList<>(
            List.of("groq", "cerebras", "deepinfra", "sambanova", "nscale", "fireworks", "huggingface",
                    "mistral", "openrouter")
    );
    private DirectProvider gemini = new DirectProvider(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "",
            List.of("gemini-2.5-flash-lite", "gemini-2.5-flash")
    );
    private DirectProvider groq = new DirectProvider(
            "https://api.groq.com/openai/v1",
            "",
            List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b"),
            List.of("llama-3.3-70b-versatile", "openai/gpt-oss-120b")
    );
    private DirectProvider cerebras = new DirectProvider(
            "https://api.cerebras.ai/v1",
            "",
            List.of("gpt-oss-120b", "llama3.1-8b"),
            List.of("gpt-oss-120b", "llama3.1-8b")
    );
    private DirectProvider deepinfra = new DirectProvider(
            "https://api.deepinfra.com/v1/openai",
            "",
            List.of("meta-llama/Llama-3.3-70B-Instruct", "openai/gpt-oss-120b"),
            List.of("meta-llama/Llama-3.3-70B-Instruct", "openai/gpt-oss-120b")
    );
    private DirectProvider sambanova = new DirectProvider(
            "https://api.sambanova.ai/v1",
            "",
            List.of("Meta-Llama-3.3-70B-Instruct", "gpt-oss-120b"),
            List.of("Meta-Llama-3.3-70B-Instruct", "gpt-oss-120b")
    );
    private DirectProvider nscale = new DirectProvider(
            "https://inference.api.nscale.com/v1",
            "",
            List.of("openai/gpt-oss-120b", "meta-llama/Llama-3.1-8B-Instruct"),
            List.of("openai/gpt-oss-120b", "meta-llama/Llama-3.1-8B-Instruct")
    );
    private DirectProvider fireworks = new DirectProvider(
            "https://api.fireworks.ai/inference/v1",
            "",
            List.of("accounts/fireworks/models/gpt-oss-120b", "accounts/fireworks/models/llama-v3p3-70b-instruct"),
            List.of("accounts/fireworks/models/gpt-oss-120b", "accounts/fireworks/models/llama-v3p3-70b-instruct")
    );
    private DirectProvider huggingface = new DirectProvider(
            "https://router.huggingface.co/v1",
            "",
            List.of("openai/gpt-oss-120b:fastest", "meta-llama/Llama-3.3-70B-Instruct:fastest"),
            List.of("openai/gpt-oss-120b:fastest", "meta-llama/Llama-3.3-70B-Instruct:fastest")
    );
    private MistralProvider mistral = new MistralProvider();
    private OpenRouterProvider openrouter = new OpenRouterProvider();

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getAvailabilityVerificationPasses() {
        return availabilityVerificationPasses;
    }

    public void setAvailabilityVerificationPasses(int availabilityVerificationPasses) {
        this.availabilityVerificationPasses = availabilityVerificationPasses;
    }

    public int getMaxProviderAttempts() {
        return maxProviderAttempts;
    }

    public void setMaxProviderAttempts(int maxProviderAttempts) {
        this.maxProviderAttempts = maxProviderAttempts;
    }

    public int getMaxModelsPerProvider() {
        return maxModelsPerProvider;
    }

    public void setMaxModelsPerProvider(int maxModelsPerProvider) {
        this.maxModelsPerProvider = maxModelsPerProvider;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public List<String> getProviderOrder() {
        return providerOrder;
    }

    public void setProviderOrder(List<String> providerOrder) {
        this.providerOrder = providerOrder;
    }

    public List<String> getPrivateProviderOrder() {
        return privateProviderOrder;
    }

    public void setPrivateProviderOrder(List<String> privateProviderOrder) {
        this.privateProviderOrder = privateProviderOrder;
    }

    public DirectProvider getGemini() {
        return gemini;
    }

    public void setGemini(DirectProvider gemini) {
        this.gemini = gemini;
    }

    public DirectProvider getGroq() {
        return groq;
    }

    public void setGroq(DirectProvider groq) {
        this.groq = groq;
    }

    public DirectProvider getCerebras() {
        return cerebras;
    }

    public void setCerebras(DirectProvider cerebras) {
        this.cerebras = cerebras;
    }

    public DirectProvider getDeepinfra() {
        return deepinfra;
    }

    public void setDeepinfra(DirectProvider deepinfra) {
        this.deepinfra = deepinfra;
    }

    public DirectProvider getSambanova() {
        return sambanova;
    }

    public void setSambanova(DirectProvider sambanova) {
        this.sambanova = sambanova;
    }

    public DirectProvider getNscale() {
        return nscale;
    }

    public void setNscale(DirectProvider nscale) {
        this.nscale = nscale;
    }

    public DirectProvider getFireworks() {
        return fireworks;
    }

    public void setFireworks(DirectProvider fireworks) {
        this.fireworks = fireworks;
    }

    public DirectProvider getHuggingface() {
        return huggingface;
    }

    public void setHuggingface(DirectProvider huggingface) {
        this.huggingface = huggingface;
    }

    public MistralProvider getMistral() {
        return mistral;
    }

    public void setMistral(MistralProvider mistral) {
        this.mistral = mistral;
    }

    public OpenRouterProvider getOpenrouter() {
        return openrouter;
    }

    public void setOpenrouter(OpenRouterProvider openrouter) {
        this.openrouter = openrouter;
    }

    public static class DirectProvider {

        private String baseUrl;
        private String apiKey;
        private List<String> publicModels;
        private List<String> privateModels;

        public DirectProvider() {
            this("", "", new ArrayList<>(), new ArrayList<>());
        }

        public DirectProvider(String baseUrl, String apiKey, List<String> publicModels) {
            this(baseUrl, apiKey, publicModels, new ArrayList<>());
        }

        public DirectProvider(
                String baseUrl,
                String apiKey,
                List<String> publicModels,
                List<String> privateModels
        ) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.publicModels = new ArrayList<>(publicModels);
            this.privateModels = new ArrayList<>(privateModels);
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public List<String> getPublicModels() {
            return publicModels;
        }

        public void setPublicModels(List<String> publicModels) {
            this.publicModels = publicModels;
        }

        public List<String> getPrivateModels() {
            return privateModels;
        }

        public void setPrivateModels(List<String> privateModels) {
            this.privateModels = privateModels;
        }
    }

    public static class MistralProvider extends DirectProvider {

        private boolean trainingOptOutConfirmed = false;

        public MistralProvider() {
            super(
                    "https://api.mistral.ai/v1",
                    "",
                    List.of("mistral-small-latest", "devstral-small-latest"),
                    List.of("mistral-small-latest", "devstral-small-latest")
            );
        }

        public boolean isTrainingOptOutConfirmed() {
            return trainingOptOutConfirmed;
        }

        public void setTrainingOptOutConfirmed(boolean trainingOptOutConfirmed) {
            this.trainingOptOutConfirmed = trainingOptOutConfirmed;
        }
    }

    public static class OpenRouterProvider extends DirectProvider {

        private boolean publicRequireZdr = false;
        private boolean privateRequireZdr = true;
        private String publicDataCollection = "allow";
        private String privateDataCollection = "deny";
        private BigDecimal maxPricePrompt = BigDecimal.ZERO;
        private BigDecimal maxPriceCompletion = BigDecimal.ZERO;
        private String httpReferer = "";
        private String appTitle = "ssuAI";
        private List<String> privateModels = new ArrayList<>();

        public OpenRouterProvider() {
            super("https://openrouter.ai/api/v1", "", new ArrayList<>());
        }

        public boolean isPublicRequireZdr() {
            return publicRequireZdr;
        }

        public void setPublicRequireZdr(boolean publicRequireZdr) {
            this.publicRequireZdr = publicRequireZdr;
        }

        public boolean isPrivateRequireZdr() {
            return privateRequireZdr;
        }

        public void setPrivateRequireZdr(boolean privateRequireZdr) {
            this.privateRequireZdr = privateRequireZdr;
        }

        public String getPublicDataCollection() {
            return publicDataCollection;
        }

        public void setPublicDataCollection(String publicDataCollection) {
            this.publicDataCollection = publicDataCollection;
        }

        public String getPrivateDataCollection() {
            return privateDataCollection;
        }

        public void setPrivateDataCollection(String privateDataCollection) {
            this.privateDataCollection = privateDataCollection;
        }

        public BigDecimal getMaxPricePrompt() {
            return maxPricePrompt;
        }

        public void setMaxPricePrompt(BigDecimal maxPricePrompt) {
            this.maxPricePrompt = maxPricePrompt;
        }

        public BigDecimal getMaxPriceCompletion() {
            return maxPriceCompletion;
        }

        public void setMaxPriceCompletion(BigDecimal maxPriceCompletion) {
            this.maxPriceCompletion = maxPriceCompletion;
        }

        public String getHttpReferer() {
            return httpReferer;
        }

        public void setHttpReferer(String httpReferer) {
            this.httpReferer = httpReferer;
        }

        public String getAppTitle() {
            return appTitle;
        }

        public void setAppTitle(String appTitle) {
            this.appTitle = appTitle;
        }

        public List<String> getPrivateModels() {
            return privateModels;
        }

        public void setPrivateModels(List<String> privateModels) {
            this.privateModels = privateModels;
        }
    }
}
