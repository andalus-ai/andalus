package tech.kayys.andalus.workflow.catalog;

/** Optional client-side filters applied to a caller-authorized catalog view. */
public class NodeCapabilityCatalogQuery {

    private String text;
    private String category;
    private String providerId;

    public String getText() { return text; }
    public NodeCapabilityCatalogQuery setText(String text) { this.text = text; return this; }
    public String getCategory() { return category; }
    public NodeCapabilityCatalogQuery setCategory(String category) { this.category = category; return this; }
    public String getProviderId() { return providerId; }
    public NodeCapabilityCatalogQuery setProviderId(String providerId) { this.providerId = providerId; return this; }
}
