package org.geotools.data.wfs.internal;

import static org.geotools.data.wfs.internal.Loggers.requestDebug;

import java.io.IOException;
import java.net.URL;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.QName;

import org.geotools.data.ows.AbstractOpenWebService;
import org.geotools.data.ows.GetCapabilitiesRequest;
import org.geotools.data.ows.HTTPClient;
import org.geotools.data.ows.Request;
import org.geotools.data.ows.Response;
import org.geotools.data.ows.Specification;
import org.geotools.data.wfs.impl.WFSServiceInfo;
import org.geotools.data.wfs.internal.GetFeatureRequest.ResultType;
import org.geotools.data.wfs.internal.v1_x.CubeWerxStrategy;
import org.geotools.data.wfs.internal.v1_x.GeoServerPre200Strategy;
import org.geotools.data.wfs.internal.v1_x.IonicStrategy;
import org.geotools.data.wfs.internal.v1_x.StrictWFS_1_x_Strategy;
import org.geotools.data.wfs.internal.v2_0.StrictWFS_2_0_Strategy;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.ows.ServiceException;
import org.geotools.util.Version;
import org.geotools.util.logging.Logging;
import org.opengis.filter.Filter;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class WFSClient extends AbstractOpenWebService<WFSGetCapabilities, QName> {

    private static final Logger LOGGER = Logging.getLogger(WFSClient.class);

    private final WFSConfig config;

    public WFSClient(URL capabilitiesURL, HTTPClient httpClient, WFSConfig config)
            throws IOException, ServiceException {
        this(capabilitiesURL, httpClient, config, (WFSGetCapabilities) null);
    }

    public WFSClient(URL capabilitiesURL, HTTPClient httpClient, WFSConfig config,
            WFSGetCapabilities capabilities) throws IOException, ServiceException {

        super(capabilitiesURL, httpClient, capabilities);
        this.config = config;
        super.specification = determineCorrectStrategy();
        ((WFSStrategy) specification).setCapabilities(super.capabilities);
    }

    protected WFSStrategy getStrategy() {
        return (WFSStrategy) super.specification;
    }

    @Override
    public WFSGetCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public WFSServiceInfo getInfo() {
        return (WFSServiceInfo) super.getInfo();
    }

    @Override
    protected WFSServiceInfo createInfo() {
        return getStrategy().getServiceInfo();
    }

    @Override
    protected FeatureTypeInfo createInfo(QName typeName) {
        return getStrategy().getFeatureTypeInfo(typeName);
    }

    @Override
    protected void setupSpecifications() {
        specs = new Specification[3];
        WFSStrategy strictWFS_1_0_0_Strategy = new StrictWFS_1_x_Strategy(Versions.v1_0_0);
        WFSStrategy strictWFS_1_1_0_Strategy = new StrictWFS_1_x_Strategy(Versions.v1_1_0);
        WFSStrategy strictWFS_2_0_0_Strategy = new StrictWFS_2_0_Strategy();

        strictWFS_1_0_0_Strategy.setConfig(config);
        strictWFS_1_1_0_Strategy.setConfig(config);
        strictWFS_2_0_0_Strategy.setConfig(config);

        specs[0] = strictWFS_1_0_0_Strategy;
        specs[1] = strictWFS_1_1_0_Strategy;
        specs[2] = strictWFS_2_0_0_Strategy;

    }

    /**
     * Determine correct WFSStrategy based on capabilities document.
     * 
     * @param getCapabilitiesRequest
     * @param capabilitiesDoc
     * @param override
     *            optional override provided by user
     * @return WFSStrategy to use
     */
    private WFSStrategy determineCorrectStrategy() {

        final Version capsVersion = new Version(capabilities.getVersion());
        Document capabilitiesDoc = capabilities.getRawDocument();

        final String override = config.getWfsStrategy();

        WFSStrategy strategy = null;
        // override
        if (!"auto".equals(override)) {
            if (override.equalsIgnoreCase("geoserver")) {
                strategy = new GeoServerPre200Strategy();
            } else if (override.equalsIgnoreCase("cubewerx")) {
                strategy = new CubeWerxStrategy();
            } else if (override.equalsIgnoreCase("ionic")) {
                strategy = new IonicStrategy();
            } else {
                LOGGER.warning("Could not handle wfs strategy override " + override
                        + " proceeding with autodetection");
            }
        }

        // auto detection
        if (strategy == null) {
            // look in comments for indication of CubeWerx server
            NodeList childNodes = capabilitiesDoc.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node child = childNodes.item(i);
                if (child.getNodeType() == Node.COMMENT_NODE) {
                    String nodeValue = child.getNodeValue();
                    nodeValue = nodeValue.toLowerCase();
                    if (nodeValue.contains("cubewerx")) {
                        strategy = new CubeWerxStrategy();
                        break;
                    }
                }
            }
        }

        if (strategy == null) {
            // Ionic declares its own namespace so that's our hook
            Element root = capabilitiesDoc.getDocumentElement();
            String ionicNs = root.getAttribute("xmlns:ionic");
            if (ionicNs != null) {
                if (ionicNs.equals("http://www.ionicsoft.com/versions/4")) {
                    strategy = new IonicStrategy();
                } else if (ionicNs.startsWith("http://www.ionicsoft.com/versions")) {
                    LOGGER.warning("Found a Ionic server but the version may not match the strategy "
                            + "we have (v.4). Ionic namespace url: " + ionicNs);
                    strategy = new IonicStrategy();
                }
            }
        }

        if (strategy == null) {
            java.net.URL capabilitiesURL = super.serverURL;
            // guess server implementation from capabilities URI
            String uri = capabilitiesURL.toExternalForm();
            /*
             * TODO: the "file:" test is a workaround for GEOT-4223
             * 
             * Gabriel Rold??n commented on GEOT-4223: sigh, yeah, that's probably that worse heuristic ever, but its inherited from the current wfs
             * 1.0 module. We've briefly discussed a better approach on the list a while back (checking for the list of supported functions to figure
             * out whether its a geoserver. Some key function names may even let you know which geoserver version it is). For the time being, please
             * feel free to apply the patch if its a blocker for you, but don't close this issue. Just add a reminder that the heuristic for geoserver
             * needs to be improved?
             */
            if (!uri.startsWith("file:") && uri.contains("geoserver")) {
                strategy = new GeoServerPre200Strategy();
            } else if (uri.contains("/ArcGIS/services/")) {
                strategy = new StrictWFS_1_x_Strategy(); // new ArcGISServerStrategy();
            }
        }

        if (strategy == null) {
            // use fallback strategy
            if (Versions.v1_0_0.equals(capsVersion)) {
                strategy = new StrictWFS_1_x_Strategy(Versions.v1_0_0);
            } else if (Versions.v1_1_0.equals(capsVersion)) {
                strategy = new StrictWFS_1_x_Strategy(Versions.v1_1_0);
            } else if (Versions.v2_0_0.equals(capsVersion)) {
                strategy = new StrictWFS_2_0_Strategy();
            } else {
                throw new IllegalArgumentException("Unsupported version: " + capsVersion);
            }
        }
        LOGGER.info("Using WFS Strategy: " + strategy.getClass().getName());
        return strategy;
    }

    public Set<QName> getRemoteTypeNames() {
        Set<QName> featureTypeNames = getStrategy().getFeatureTypeNames();
        return featureTypeNames;
    }

    public boolean supportsTransaction(QName typeName) {
        return getStrategy().supportsTransaction(typeName);
    }

    public boolean canLimit() {
        return true;
    }

    public boolean canFilter() {
        return true;
    }

    public boolean canRetype() {
        return true;
    }

    public boolean canSort() {
        return true;
    }

    public ReferencedEnvelope getBounds(QName typeName, CoordinateReferenceSystem targetCrs) {

        WFSStrategy strategy = getStrategy();
        final FeatureTypeInfo typeInfo = strategy.getFeatureTypeInfo(typeName);
        ReferencedEnvelope nativeBounds = typeInfo.getBounds();

        ReferencedEnvelope bounds;
        try {
            bounds = nativeBounds.transform(targetCrs, true);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Can't transform native bounds of " + typeName + ": " + e.getMessage());
            try {
                bounds = typeInfo.getWGS84BoundingBox().transform(targetCrs, true);
            } catch (Exception ew) {
                LOGGER.log(Level.WARNING,
                        "Can't transform wgs84 bounds of " + typeName + ": " + e.getMessage());
                bounds = new ReferencedEnvelope(targetCrs);
            }
        }
        return bounds;
    }

    public boolean canCount() {
        return getStrategy().supports(ResultType.HITS);
    }

    public GetFeatureRequest createGetFeatureRequest() {
        WFSStrategy strategy = getStrategy();
        return new GetFeatureRequest(config, strategy);
    }

    @Override
    protected Response internalIssueRequest(Request request) throws IOException, WFSException {
        Response response;
        try {
            response = super.internalIssueRequest(request);
        } catch (ServiceException e) {
            throw new IOException(e);
        }
        return response;
    }

    @Override
    public GetCapabilitiesResponse issueRequest(GetCapabilitiesRequest request) throws IOException,
            ServiceException {
        return (GetCapabilitiesResponse) internalIssueRequest(request);
    }

    public TransactionRequest createTransaction() {
        WFSStrategy strategy = getStrategy();
        return new TransactionRequest(config, strategy);
    }

    public TransactionResponse issueTransaction(TransactionRequest request) throws IOException {

        requestDebug("Sending Transaction request to ", request.getFinalURL());

        Response response = internalIssueRequest(request);
        return (TransactionResponse) response;
    }

    public GetFeatureResponse issueRequest(GetFeatureRequest request) throws IOException {

        requestDebug("Sending GetFeature request to ", request.getFinalURL());

        Response response = internalIssueRequest(request);
        return (GetFeatureResponse) response;
    }

    public DescribeFeatureTypeRequest createDescribeFeatureTypeRequest() {
        return new DescribeFeatureTypeRequest(config, getStrategy());
    }

    public DescribeFeatureTypeResponse issueRequest(DescribeFeatureTypeRequest request)
            throws IOException {

        requestDebug("Sending DFT request to ", request.getFinalURL());

        Response response = internalIssueRequest(request);
        return (DescribeFeatureTypeResponse) response;
    }

    /**
     * Splits the filter provided by the geotools query into the server supported and unsupported
     * ones.
     * 
     * @param typeName
     * 
     * @return a two-element array where the first element is the supported filter and the second
     *         the one to post-process
     * @see org.geotools.data.wfs.internal.WFSStrategy#splitFilters(org.opengis.filter.Filter)
     */
    public Filter[] splitFilters(QName typeName, Filter filter) {
        return getStrategy().splitFilters(typeName, filter);
    }
    public CoordinateReferenceSystem getDefaultCRS(QName typeName) {
        final WFSStrategy strategy = getStrategy();
        FeatureTypeInfo typeInfo = strategy.getFeatureTypeInfo(typeName);
        CoordinateReferenceSystem crs = typeInfo.getCRS();
        return crs;
    }
}
