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

package io.bisq.gui.main.portfolio.pendingtrades;

import com.google.inject.Inject;
import io.bisq.app.Log;
import io.bisq.arbitration.DisputeAlreadyOpenException;
import io.bisq.arbitration.DisputeManager;
import io.bisq.btc.wallet.BtcWalletService;
import io.bisq.btc.wallet.TradeWalletService;
import io.bisq.common.handlers.ErrorMessageHandler;
import io.bisq.common.handlers.FaultHandler;
import io.bisq.common.handlers.ResultHandler;
import io.bisq.crypto.KeyRing;
import io.bisq.gui.Navigation;
import io.bisq.gui.common.model.ActivatableDataModel;
import io.bisq.gui.main.MainView;
import io.bisq.gui.main.disputes.DisputesView;
import io.bisq.gui.main.overlays.notifications.NotificationCenter;
import io.bisq.gui.main.overlays.popups.Popup;
import io.bisq.gui.main.overlays.windows.SelectDepositTxWindow;
import io.bisq.gui.main.overlays.windows.WalletPasswordWindow;
import io.bisq.locale.Res;
import io.bisq.offer.Offer;
import io.bisq.p2p.storage.P2PService;
import io.bisq.payload.arbitration.Arbitrator;
import io.bisq.payload.arbitration.Dispute;
import io.bisq.payload.payment.PaymentAccountContractData;
import io.bisq.payload.trade.offer.OfferPayload;
import io.bisq.provider.fee.FeeService;
import io.bisq.trade.BuyerTrade;
import io.bisq.trade.SellerTrade;
import io.bisq.trade.Trade;
import io.bisq.trade.TradeManager;
import io.bisq.user.Preferences;
import io.bisq.user.User;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.bitcoinj.core.BlockChainListener;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class PendingTradesDataModel extends ActivatableDataModel {
    public final TradeManager tradeManager;
    public final BtcWalletService btcWalletService;
    private final TradeWalletService tradeWalletService;
    private final FeeService feeService;
    private final User user;
    private final KeyRing keyRing;
    public final DisputeManager disputeManager;
    private final P2PService p2PService;
    public final Navigation navigation;
    public final WalletPasswordWindow walletPasswordWindow;
    private final NotificationCenter notificationCenter;

    final ObservableList<PendingTradesListItem> list = FXCollections.observableArrayList();
    private final ListChangeListener<Trade> tradesListChangeListener;
    private boolean isOfferer;

    final ObjectProperty<PendingTradesListItem> selectedItemProperty = new SimpleObjectProperty<>();
    public final StringProperty txId = new SimpleStringProperty();
    public final Preferences preferences;
    private boolean activated;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, initialization
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public PendingTradesDataModel(TradeManager tradeManager, BtcWalletService btcWalletService, TradeWalletService tradeWalletService, FeeService feeService,
                                  User user, KeyRing keyRing, DisputeManager disputeManager, Preferences preferences, P2PService p2PService,
                                  Navigation navigation, WalletPasswordWindow walletPasswordWindow, NotificationCenter notificationCenter) {
        this.tradeManager = tradeManager;
        this.btcWalletService = btcWalletService;
        this.tradeWalletService = tradeWalletService;
        this.feeService = feeService;
        this.user = user;
        this.keyRing = keyRing;
        this.disputeManager = disputeManager;
        this.preferences = preferences;
        this.p2PService = p2PService;
        this.navigation = navigation;
        this.walletPasswordWindow = walletPasswordWindow;
        this.notificationCenter = notificationCenter;

        tradesListChangeListener = change -> onListChanged();
        notificationCenter.setSelectItemByTradeIdConsumer(this::selectItemByTradeId);
    }

    @Override
    protected void activate() {
        tradeManager.getTrades().addListener(tradesListChangeListener);
        onListChanged();
        if (selectedItemProperty.get() != null)
            notificationCenter.setSelectedTradeId(selectedItemProperty.get().getTrade().getId());

        activated = true;
    }

    @Override
    protected void deactivate() {
        tradeManager.getTrades().removeListener(tradesListChangeListener);
        notificationCenter.setSelectedTradeId(null);
        activated = false;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    void onSelectItem(PendingTradesListItem item) {
        doSelectItem(item);
    }

    public void onPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        checkNotNull(getTrade(), "trade must not be null");
        checkArgument(getTrade() instanceof BuyerTrade, "Check failed: trade instanceof BuyerTrade");
        checkArgument(getTrade().getDisputeState() == Trade.DisputeState.NONE, "Check failed: trade.getDisputeState() == Trade.DisputeState.NONE");
        ((BuyerTrade) getTrade()).onFiatPaymentStarted(resultHandler, errorMessageHandler);
    }

    public void onFiatPaymentReceived(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        checkNotNull(getTrade(), "trade must not be null");
        checkArgument(getTrade() instanceof SellerTrade, "Check failed: trade not instanceof SellerTrade");
        if (getTrade().getDisputeState() == Trade.DisputeState.NONE)
            ((SellerTrade) getTrade()).onFiatPaymentReceived(resultHandler, errorMessageHandler);
    }

    public void onWithdrawRequest(String toAddress, Coin amount, Coin fee, KeyParameter aesKey, ResultHandler resultHandler, FaultHandler faultHandler) {
        checkNotNull(getTrade(), "trade must not be null");

        if (toAddress != null && toAddress.length() > 0) {
            tradeManager.onWithdrawRequest(
                    toAddress,
                    amount,
                    fee,
                    aesKey,
                    getTrade(),
                    () -> {
                        resultHandler.handleResult();
                        selectBestItem();
                    },
                    (errorMessage, throwable) -> {
                        log.error(errorMessage);
                        faultHandler.handleFault(errorMessage, throwable);
                    });
        } else {
            faultHandler.handleFault(Res.get("portfolio.pending.noReceiverAddressDefined"), null);
        }
    }

    public void onOpenDispute() {
        tryOpenDispute(false);
    }

    public void onOpenSupportTicket() {
        tryOpenDispute(true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    public PendingTradesListItem getSelectedItem() {
        return selectedItemProperty.get() != null ? selectedItemProperty.get() : null;
    }

    @Nullable
    public Trade getTrade() {
        return selectedItemProperty.get() != null ? selectedItemProperty.get().getTrade() : null;
    }

    @Nullable
    Offer getOffer() {
        return getTrade() != null ? getTrade().getOffer() : null;
    }

    boolean isBuyOffer() {
        return getOffer() != null && getOffer().getDirection() == OfferPayload.Direction.BUY;
    }

    boolean isBuyer() {
        return (isOfferer(getOffer()) && isBuyOffer())
                || (!isOfferer(getOffer()) && !isBuyOffer());
    }

    boolean isOfferer(Offer offer) {
        return tradeManager.isMyOffer(offer);
    }

    private boolean isOfferer() {
        return isOfferer;
    }

    Coin getTotalFees() {
        Trade trade = getTrade();
        if (trade != null) {
            if (isOfferer()) {
                Offer offer = trade.getOffer();
                return offer.getCreateOfferFee().add(offer.getTxFee());
            } else {
                return trade.getTakeOfferFee().add(trade.getTxFee().multiply(3));
            }
        } else {
            log.error("Trade is null at getTotalFees");
            return Coin.ZERO;
        }
    }

    public String getCurrencyCode() {
        return getOffer() != null ? getOffer().getCurrencyCode() : "";
    }

    public OfferPayload.Direction getDirection(Offer offer) {
        isOfferer = tradeManager.isMyOffer(offer);
        return isOfferer ? offer.getDirection() : offer.getMirroredDirection();
    }

    void addBlockChainListener(BlockChainListener blockChainListener) {
        tradeWalletService.addBlockChainListener(blockChainListener);
    }

    void removeBlockChainListener(BlockChainListener blockChainListener) {
        tradeWalletService.removeBlockChainListener(blockChainListener);
    }

    public long getLockTime() {
        return getTrade() != null ? getTrade().getLockTimeAsBlockHeight() : 0;
    }

    public int getBestChainHeight() {
        return tradeWalletService.getBestChainHeight();
    }

    @Nullable
    public PaymentAccountContractData getSellersPaymentAccountContractData() {
        if (getTrade() != null && getTrade().getContract() != null)
            return getTrade().getContract().getSellerPaymentAccountContractData();
        else
            return null;
    }

    public String getReference() {
        return getOffer() != null ? getOffer().getShortId() : "";
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onListChanged() {
        Log.traceCall();
        list.clear();
        list.addAll(tradeManager.getTrades().stream().map(PendingTradesListItem::new).collect(Collectors.toList()));

        // we sort by date, earliest first
        list.sort((o1, o2) -> o2.getTrade().getDate().compareTo(o1.getTrade().getDate()));

        selectBestItem();
    }

    private void selectBestItem() {
        if (list.size() == 1)
            doSelectItem(list.get(0));
        else if (list.size() > 1 && (selectedItemProperty.get() == null || !list.contains(selectedItemProperty.get())))
            doSelectItem(list.get(0));
        else if (list.size() == 0)
            doSelectItem(null);
    }

    private void selectItemByTradeId(String tradeId) {
        if (activated)
            list.stream().filter(e -> e.getTrade().getId().equals(tradeId)).findAny().ifPresent(this::doSelectItem);
    }

    private void doSelectItem(PendingTradesListItem item) {
        if (item != null) {
            Trade trade = item.getTrade();
            isOfferer = tradeManager.isMyOffer(trade.getOffer());
            if (trade.getDepositTx() != null)
                txId.set(trade.getDepositTx().getHashAsString());
            else
                txId.set("");
            notificationCenter.setSelectedTradeId(trade.getId());
        } else {
            notificationCenter.setSelectedTradeId(null);
        }
        selectedItemProperty.set(item);
    }

    private void tryOpenDispute(boolean isSupportTicket) {
        if (getTrade() != null) {
            Transaction depositTx = getTrade().getDepositTx();
            if (depositTx != null) {
                doOpenDispute(isSupportTicket, getTrade().getDepositTx());
            } else {
                log.info("Trade.depositTx is null. We try to find the tx in our wallet.");
                List<Transaction> candidates = new ArrayList<>();
                List<Transaction> transactions = btcWalletService.getRecentTransactions(100, true);
                transactions.stream().forEach(transaction -> {
                    Coin valueSentFromMe = btcWalletService.getValueSentFromMeForTransaction(transaction);
                    if (!valueSentFromMe.isZero()) {
                        // spending tx
                        // MS tx
                        candidates.addAll(transaction.getOutputs().stream()
                                .filter(transactionOutput -> !btcWalletService.isTransactionOutputMine(transactionOutput))
                                .filter(transactionOutput -> transactionOutput.getScriptPubKey().isPayToScriptHash())
                                .map(transactionOutput -> transaction)
                                .collect(Collectors.toList()));
                    }
                });

                if (candidates.size() == 1)
                    doOpenDispute(isSupportTicket, candidates.get(0));
                else if (candidates.size() > 1)
                    new SelectDepositTxWindow().transactions(candidates)
                            .onSelect(transaction -> doOpenDispute(isSupportTicket, transaction))
                            .closeButtonText(Res.get("shared.cancel"))
                            .show();
                else
                    log.error("Trade.depositTx is null and we did not find any MultiSig transaction.");
            }
        } else {
            log.error("Trade is null");
        }
    }

    private void doOpenDispute(boolean isSupportTicket, Transaction depositTx) {
        Log.traceCall("depositTx=" + depositTx);
        byte[] depositTxSerialized = null;
        byte[] payoutTxSerialized = null;
        String depositTxHashAsString = null;
        String payoutTxHashAsString = null;
        if (depositTx != null) {
            depositTxSerialized = depositTx.bitcoinSerialize();
            depositTxHashAsString = depositTx.getHashAsString();
        } else {
            log.warn("depositTx is null");
        }
        Trade trade = getTrade();
        if (trade != null) {
            Transaction payoutTx = trade.getPayoutTx();
            if (payoutTx != null) {
                payoutTxSerialized = payoutTx.bitcoinSerialize();
                payoutTxHashAsString = payoutTx.getHashAsString();
            } else {
                log.debug("payoutTx is null at doOpenDispute");
            }

            final Arbitrator acceptedArbitratorByAddress = user.getAcceptedArbitratorByAddress(trade.getArbitratorNodeAddress());
            checkNotNull(acceptedArbitratorByAddress, "acceptedArbitratorByAddress must no tbe null");
            Dispute dispute = new Dispute(disputeManager.getDisputeStorage(),
                    trade.getId(),
                    keyRing.getPubKeyRing().hashCode(), // traderId
                    trade.getOffer().getDirection() == OfferPayload.Direction.BUY ? isOfferer : !isOfferer,
                    isOfferer,
                    keyRing.getPubKeyRing(),
                    trade.getDate(),
                    trade.getContract(),
                    trade.getContractHash(),
                    depositTxSerialized,
                    payoutTxSerialized,
                    depositTxHashAsString,
                    payoutTxHashAsString,
                    trade.getContractAsJson(),
                    trade.getOffererContractSignature(),
                    trade.getTakerContractSignature(),
                    acceptedArbitratorByAddress.getPubKeyRing(),
                    isSupportTicket
            );

            trade.setDisputeState(Trade.DisputeState.DISPUTE_REQUESTED);
            if (p2PService.isBootstrapped()) {
                sendOpenNewDisputeMessage(dispute, false);
            } else {
                new Popup().information(Res.get("popup.warning.notFullyConnected")).show();
            }
        } else {
            log.warn("trade is null at doOpenDispute");
        }
    }

    private void sendOpenNewDisputeMessage(Dispute dispute, boolean reOpen) {
        disputeManager.sendOpenNewDisputeMessage(dispute,
                reOpen,
                () -> navigation.navigateTo(MainView.class, DisputesView.class),
                (errorMessage, throwable) -> {
                    if ((throwable instanceof DisputeAlreadyOpenException)) {
                        errorMessage += "\n\n" + Res.get("portfolio.pending.openAgainDispute.msg");
                        new Popup().warning(errorMessage)
                                .actionButtonText(Res.get("portfolio.pending.openAgainDispute.button"))
                                .onAction(() -> sendOpenNewDisputeMessage(dispute, true))
                                .closeButtonText(Res.get("shared.cancel"))
                                .show();
                    } else {
                        new Popup().warning(errorMessage).show();
                    }
                });
    }
}
