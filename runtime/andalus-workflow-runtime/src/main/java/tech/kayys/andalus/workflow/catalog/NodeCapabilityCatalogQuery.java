package tech.kayys.andalus.workflow.catalog;

/** Optional filters applied to a caller-authorized capability catalog view. */
public class NodeCapabilityCatalogQuery {

    private String text;
    private String category;
    private String providerId;
    private String frameworkVersion;

    public String getText() { return text; }
    public NodeCapabilityCatalogQuery setText(String text) { this.text = text; return this; }
    public String getCategory() { return category; }
    public NodeCapabilityCatalogQuery setCategory(String category) { this.category = category; return this; }
    public String getProviderId() { return providerId; }
    public NodeCapabilityCatalogQuery setProviderId(String providerId) { this.providerId = providerId; return this; }
    public String getFrameworkVersion() { return frameworkVersion; }
    public NodeCapabilityCatalogQuery setFrameworkVersion(String frameworkVersion) {
        this.frameworkVersion = frameworkVersion;
        return this;
    }
}
