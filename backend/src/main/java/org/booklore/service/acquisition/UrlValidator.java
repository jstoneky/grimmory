package org.booklore.service.acquisition;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class UrlValidator {

    public void validateOutboundUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Only http/https URLs are allowed");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Invalid URL: no host");
            }
            blockCloudMetadata(host);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL: " + e.getMessage());
        }
    }

    // Private/loopback addresses are intentionally allowed — this is a self-hosted
    // application where SABnzbd/NZBHydra commonly run on localhost or the LAN.
    // Only cloud metadata endpoints that legitimate users would never need are blocked.
    private void blockCloudMetadata(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip.startsWith("169.254.") || ip.equals("100.100.100.200")) {
                throw new IllegalArgumentException("Connections to cloud metadata addresses are not allowed");
            }
        } catch (UnknownHostException e) {
            // Unresolvable host — let the HTTP call fail naturally
        }
    }
}
