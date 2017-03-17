/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.offer;

import com.google.inject.name.Named;
import io.bisq.app.AppOptionKeys;
import io.bisq.common.UserThread;
import io.bisq.common.handlers.ErrorMessageHandler;
import io.bisq.common.handlers.ResultHandler;
import io.bisq.common.util.Utilities;
import io.bisq.p2p.BootstrapListener;
import io.bisq.p2p.storage.HashMapChangedListener;
import io.bisq.p2p.storage.P2PService;
import io.bisq.payload.p2p.storage.ProtectedStorageEntry;
import io.bisq.payload.trade.offer.OfferPayload;
import io.bisq.provider.price.PriceFeedService;
import io.bisq.storage.PlainTextWrapper;
import io.bisq.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles storage and retrieval of offers.
 * Uses an invalidation flag to only request the full offer map in case there was a change (anyone has added or removed an offer).
 */
public class OfferBookService {
    private static final Logger log = LoggerFactory.getLogger(OfferBookService.class);

    public interface OfferBookChangedListener {
        void onAdded(Offer offer);

        void onRemoved(Offer offer);
    }

    private final P2PService p2PService;
    private PriceFeedService priceFeedService;
    private final Storage<PlainTextWrapper> offersJsonStorage;
    private final List<OfferBookChangedListener> offerBookChangedListeners = new LinkedList<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OfferBookService(P2PService p2PService,
                            PriceFeedService priceFeedService,
                            Storage<PlainTextWrapper> offersJsonStorage,
                            @Named(AppOptionKeys.DUMP_STATISTICS) boolean dumpStatistics) {
        this.p2PService = p2PService;
        this.priceFeedService = priceFeedService;
        this.offersJsonStorage = offersJsonStorage;

        p2PService.addHashSetChangedListener(new HashMapChangedListener() {
            @Override
            public void onAdded(ProtectedStorageEntry data) {
                offerBookChangedListeners.stream().forEach(listener -> {
                    if (data.getStoragePayload() instanceof OfferPayload) {
                        Offer offer = new Offer((OfferPayload) data.getStoragePayload());
                        offer.setPriceFeedService(priceFeedService);
                        listener.onAdded(offer);
                    }
                });
            }

            @Override
            public void onRemoved(ProtectedStorageEntry data) {
                offerBookChangedListeners.stream().forEach(listener -> {
                    if (data.getStoragePayload() instanceof OfferPayload) {
                        Offer offer = new Offer((OfferPayload) data.getStoragePayload());
                        offer.setPriceFeedService(priceFeedService);
                        listener.onRemoved(offer);
                    }
                });
            }
        });

        if (dumpStatistics) {
            this.offersJsonStorage.initWithFileName("offers_statistics.json");

            p2PService.addP2PServiceListener(new BootstrapListener() {
                @Override
                public void onBootstrapComplete() {
                    addOfferBookChangedListener(new OfferBookChangedListener() {
                        @Override
                        public void onAdded(Offer offer) {
                            doDumpStatistics();
                        }

                        @Override
                        public void onRemoved(Offer offer) {
                            doDumpStatistics();
                        }
                    });
                    UserThread.runAfter(OfferBookService.this::doDumpStatistics, 1);
                }
            });
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        boolean result = p2PService.addData(offer.getOfferPayload(), true);
        if (result) {
            log.trace("Add offer to network was successful. OfferPayload ID = " + offer.getId());
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("Add offer failed");
        }
    }

    public void refreshTTL(OfferPayload offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        boolean result = p2PService.refreshTTL(offer, true);
        if (result) {
            log.trace("Refresh TTL was successful. OfferPayload ID = " + offer.getId());
            resultHandler.handleResult();
        } else {
            errorMessageHandler.handleErrorMessage("Refresh TTL failed.");
        }
    }

    public void removeOffer(OfferPayload offer, @Nullable ResultHandler resultHandler, @Nullable ErrorMessageHandler errorMessageHandler) {
        if (p2PService.removeData(offer, true)) {
            log.trace("Remove offer from network was successful. OfferPayload ID = " + offer.getId());
            if (resultHandler != null)
                resultHandler.handleResult();
        } else {
            if (errorMessageHandler != null)
                errorMessageHandler.handleErrorMessage("Remove offer failed");
        }
    }

    public List<Offer> getOffers() {
        return p2PService.getDataMap().values().stream()
                .filter(data -> data.getStoragePayload() instanceof OfferPayload)
                .map(data -> {
                    Offer offer = new Offer((OfferPayload) data.getStoragePayload());
                    offer.setPriceFeedService(priceFeedService);
                    return offer;
                })
                .collect(Collectors.toList());
    }

    public void removeOfferAtShutDown(OfferPayload offer) {
        log.debug("removeOfferAtShutDown " + offer);
        removeOffer(offer, null, null);
    }

    public boolean isBootstrapped() {
        return p2PService.isBootstrapped();
    }

    public void addOfferBookChangedListener(OfferBookChangedListener offerBookChangedListener) {
        offerBookChangedListeners.add(offerBookChangedListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void doDumpStatistics() {
        // We filter the case that it is a MarketBasedPrice but the price is not available
        // That should only be possible if the price feed provider is not available
        final List<OfferForJson> offerForJsonList = getOffers().stream()
                .filter(offer -> !offer.isUseMarketBasedPrice() || priceFeedService.getMarketPrice(offer.getCurrencyCode()) != null)
                .map(offer -> {
                    try {
                        return new OfferForJson(offer.getDirection(),
                                offer.getCurrencyCode(),
                                offer.getMinAmount(),
                                offer.getAmount(),
                                offer.getPrice(),
                                offer.getDate(),
                                offer.getId(),
                                offer.isUseMarketBasedPrice(),
                                offer.getMarketPriceMargin(),
                                offer.getPaymentMethod(),
                                offer.getOfferFeePaymentTxID()
                        );
                    } catch (Throwable t) {
                        // In case a offer was corrupted with null values we ignore it
                        return null;
                    }
                })
                .filter(e -> e != null)
                .collect(Collectors.toList());
        offersJsonStorage.queueUpForSave(new PlainTextWrapper(Utilities.objectToJson(offerForJsonList)), 5000);
    }
}