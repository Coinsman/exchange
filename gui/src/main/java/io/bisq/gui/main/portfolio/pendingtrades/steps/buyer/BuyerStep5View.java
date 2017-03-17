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

package io.bisq.gui.main.portfolio.pendingtrades.steps.buyer;

import io.bisq.app.DevEnv;
import io.bisq.app.Log;
import io.bisq.btc.AddressEntry;
import io.bisq.btc.AddressEntryException;
import io.bisq.btc.InsufficientFundsException;
import io.bisq.btc.Restrictions;
import io.bisq.btc.wallet.BtcWalletService;
import io.bisq.common.UserThread;
import io.bisq.common.handlers.FaultHandler;
import io.bisq.common.handlers.ResultHandler;
import io.bisq.common.util.Tuple2;
import io.bisq.gui.components.InputTextField;
import io.bisq.gui.main.MainView;
import io.bisq.gui.main.funds.FundsView;
import io.bisq.gui.main.funds.transactions.TransactionsView;
import io.bisq.gui.main.overlays.notifications.Notification;
import io.bisq.gui.main.overlays.popups.Popup;
import io.bisq.gui.main.portfolio.pendingtrades.PendingTradesViewModel;
import io.bisq.gui.main.portfolio.pendingtrades.steps.TradeStepView;
import io.bisq.gui.util.BSFormatter;
import io.bisq.gui.util.Layout;
import io.bisq.locale.Res;
import io.bisq.util.CoinUtil;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.concurrent.TimeUnit;

import static io.bisq.gui.util.FormBuilder.*;

public class BuyerStep5View extends TradeStepView {
    private final ChangeListener<Boolean> focusedPropertyListener;

    private Label btcTradeAmountLabel;
    private Label fiatTradeAmountLabel;
    private InputTextField withdrawAddressTextField;
    private Button withdrawToExternalWalletButton, useSavingsWalletButton;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, Initialisation
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerStep5View(PendingTradesViewModel model) {
        super(model);

        focusedPropertyListener = (ov, oldValue, newValue) -> {
            if (oldValue && !newValue)
                model.withdrawAddressFocusOut(withdrawAddressTextField.getText());
        };
    }

    @Override
    public void activate() {
        super.activate();

        // TODO valid. handler need improvement
        //withdrawAddressTextField.focusedProperty().addListener(focusedPropertyListener);
        //withdrawAddressTextField.setValidator(model.getBtcAddressValidator());
        // withdrawButton.disableProperty().bind(model.getWithdrawalButtonDisable());

        // We need to handle both cases: Address not set and address already set (when returning from other view)
        // We get address validation after focus out, so first make sure we loose focus and then set it again as hint for user to put address in
        //TODO app wide focus
       /* UserThread.execute(() -> {
            withdrawAddressTextField.requestFocus();
           UserThread.execute(() -> {
                this.requestFocus();
                UserThread.execute(() -> withdrawAddressTextField.requestFocus());
            });
        });*/

        hideNotificationGroup();
    }

    @Override
    public void deactivate() {
        Log.traceCall();
        super.deactivate();
        //withdrawAddressTextField.focusedProperty().removeListener(focusedPropertyListener);
        // withdrawButton.disableProperty().unbind();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Content
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected void addContent() {
        addTitledGroupBg(gridPane, gridRow, 4, Res.get("portfolio.pending.step5_buyer.groupTitle"), 0);
        Tuple2<Label, TextField> btcTradeAmountPair = addLabelTextField(gridPane, gridRow, getBtcTradeAmountLabel(), model.getTradeVolume(), Layout.FIRST_ROW_DISTANCE);
        btcTradeAmountLabel = btcTradeAmountPair.first;

        Tuple2<Label, TextField> fiatTradeAmountPair = addLabelTextField(gridPane, ++gridRow, getFiatTradeAmountLabel(), model.getFiatVolume());
        fiatTradeAmountLabel = fiatTradeAmountPair.first;
        addLabelTextField(gridPane, ++gridRow, Res.get("portfolio.pending.step5_buyer.totalPaid"), model.getTotalFees());
        addLabelTextField(gridPane, ++gridRow, Res.get("portfolio.pending.step5_buyer.refunded"), model.getSecurityDeposit());
        addTitledGroupBg(gridPane, ++gridRow, 2, Res.get("portfolio.pending.step5_buyer.withdrawBTC"), Layout.GROUP_DISTANCE);
        addLabelTextField(gridPane, gridRow, Res.get("portfolio.pending.step5_buyer.amount"), model.getPayoutAmount(), Layout.FIRST_ROW_AND_GROUP_DISTANCE);
        withdrawAddressTextField = addLabelInputTextField(gridPane, ++gridRow, Res.get("portfolio.pending.step5_buyer.withdrawToAddress")).second;

        HBox hBox = new HBox();
        hBox.setSpacing(10);
        useSavingsWalletButton = new Button(Res.get("portfolio.pending.step5_buyer.moveToBisqWallet"));
        useSavingsWalletButton.setDefaultButton(false);
        Label label = new Label(Res.get("shared.OR"));
        label.setPadding(new Insets(5, 0, 0, 0));
        withdrawToExternalWalletButton = new Button(Res.get("portfolio.pending.step5_buyer.withdrawExternal"));
        withdrawToExternalWalletButton.setDefaultButton(false);
        hBox.getChildren().addAll(useSavingsWalletButton, label, withdrawToExternalWalletButton);
        GridPane.setRowIndex(hBox, ++gridRow);
        GridPane.setColumnIndex(hBox, 1);
        GridPane.setMargin(hBox, new Insets(15, 10, 0, 0));
        gridPane.getChildren().add(hBox);

        useSavingsWalletButton.setOnAction(e -> {
            model.dataModel.btcWalletService.swapTradeEntryToAvailableEntry(trade.getId(), AddressEntry.Context.TRADE_PAYOUT);

            handleTradeCompleted();
            model.dataModel.tradeManager.addTradeToClosedTrades(trade);
        });
        withdrawToExternalWalletButton.setOnAction(e -> reviewWithdrawal());

        if (DevEnv.DEV_MODE) {
            withdrawAddressTextField.setText("mpaZiEh8gSr4LcH11FrLdRY57aArt88qtg");
        } else {
            String key = "tradeCompleted" + trade.getId();
            if (!DevEnv.DEV_MODE && preferences.showAgain(key)) {
                preferences.dontShowAgain(key, true);
                new Notification().headLine(Res.get("notification.tradeCompleted.headline"))
                        .notification(Res.get("notification.tradeCompleted.msg"))
                        .autoClose()
                        .show();
            }
        }
    }

    private void reviewWithdrawal() {
        Coin amount = trade.getPayoutAmount();
        BtcWalletService walletService = model.dataModel.btcWalletService;

        AddressEntry fromAddressesEntry = walletService.getOrCreateAddressEntry(trade.getId(), AddressEntry.Context.TRADE_PAYOUT);
        String fromAddresses = fromAddressesEntry.getAddressString();
        String toAddresses = withdrawAddressTextField.getText();

        // TODO at some error situation it can be that the funds are already paid out and we get stuck here
        // need handling to remove the trade (planned for next release)
        Coin balance = walletService.getBalanceForAddress(fromAddressesEntry.getAddress());
        try {
            Transaction feeEstimationTransaction = walletService.getFeeEstimationTransaction(fromAddresses, toAddresses, amount, AddressEntry.Context.TRADE_PAYOUT);
            Coin fee = feeEstimationTransaction.getFee();
            Coin receiverAmount = amount.subtract(fee);
            if (balance.isZero()) {
                new Popup().warning(Res.get("portfolio.pending.step5_buyer.alreadyWithdrawn")).show();
                model.dataModel.tradeManager.addTradeToClosedTrades(trade);
            } else {
                if (toAddresses.isEmpty()) {
                    validateWithdrawAddress();
                } else if (Restrictions.isAboveDust(amount, fee)) {
                    if (DevEnv.DEV_MODE) {
                        doWithdrawal(amount, fee);
                    } else {
                        BSFormatter formatter = model.formatter;
                        String key = "reviewWithdrawalAtTradeComplete";
                        if (!DevEnv.DEV_MODE && preferences.showAgain(key)) {
                            int txSize = feeEstimationTransaction.bitcoinSerialize().length;
                            double feePerByte = CoinUtil.getFeePerByte(fee, txSize);
                            double kb = txSize / 1000d;
                            String recAmount = formatter.formatCoinWithCode(receiverAmount);
                            new Popup().headLine(Res.get("portfolio.pending.step5_buyer.confirmWithdrawal"))
                                    .confirmation(Res.get("shared.sendFundsDetailsWithFee",
                                            formatter.formatCoinWithCode(amount),
                                            fromAddresses,
                                            toAddresses,
                                            formatter.formatCoinWithCode(fee),
                                            feePerByte,
                                            kb,
                                            recAmount))
                                    .actionButtonText(Res.get("shared.yes"))
                                    .onAction(() -> doWithdrawal(amount, fee))
                                    .closeButtonText(Res.get("shared.cancel"))
                                    .onClose(() -> {
                                        useSavingsWalletButton.setDisable(false);
                                        withdrawToExternalWalletButton.setDisable(false);
                                    })
                                    .dontShowAgainId(key, preferences)
                                    .show();
                        } else {
                            doWithdrawal(amount, fee);
                        }
                    }

                } else {
                    new Popup().warning(Res.get("portfolio.pending.step5_buyer.amountTooLow")).show();
                }
            }
        } catch (AddressFormatException e) {
            validateWithdrawAddress();
        } catch (AddressEntryException e) {
            log.error(e.getMessage());
        } catch (InsufficientFundsException e) {
            log.error(e.getMessage());
            e.printStackTrace();
            new Popup().warning(e.getMessage()).show();
        }
    }

    private void doWithdrawal(Coin amount, Coin fee) {
        String toAddress = withdrawAddressTextField.getText();
        ResultHandler resultHandler = this::handleTradeCompleted;
        FaultHandler faultHandler = (errorMessage, throwable) -> {
            useSavingsWalletButton.setDisable(false);
            withdrawToExternalWalletButton.setDisable(false);
            if (throwable != null && throwable.getMessage() != null)
                new Popup().error(errorMessage + "\n\n" + throwable.getMessage()).show();
            else
                new Popup().error(errorMessage).show();
        };
        if (model.dataModel.btcWalletService.isEncrypted()) {
            UserThread.runAfter(() -> model.dataModel.walletPasswordWindow.onAesKey(aesKey ->
                    doWithdrawRequest(toAddress, amount, fee, aesKey, resultHandler, faultHandler))
                    .show(), 300, TimeUnit.MILLISECONDS);
        } else
            doWithdrawRequest(toAddress, amount, fee, null, resultHandler, faultHandler);
    }

    private void doWithdrawRequest(String toAddress, Coin amount, Coin fee, KeyParameter aesKey, ResultHandler resultHandler, FaultHandler faultHandler) {
        useSavingsWalletButton.setDisable(true);
        withdrawToExternalWalletButton.setDisable(true);
        model.dataModel.onWithdrawRequest(toAddress,
                amount,
                fee,
                aesKey,
                resultHandler,
                faultHandler);
    }

    private void handleTradeCompleted() {
        if (!DevEnv.DEV_MODE) {
            String key = "tradeCompleteWithdrawCompletedInfo";
            new Popup().headLine(Res.get("portfolio.pending.step5_buyer.withdrawalCompleted.headline"))
                    .feedback(Res.get("portfolio.pending.step5_buyer.withdrawalCompleted.msg"))
                    .actionButtonTextWithGoTo("navigation.funds.transactions")
                    .onAction(() -> model.dataModel.navigation.navigateTo(MainView.class, FundsView.class, TransactionsView.class))
                    .dontShowAgainId(key, preferences)
                    .show();
        }
        useSavingsWalletButton.setDisable(true);
        withdrawToExternalWalletButton.setDisable(true);
    }

    private void validateWithdrawAddress() {
        withdrawAddressTextField.setValidator(model.btcAddressValidator);
        withdrawAddressTextField.requestFocus();
        useSavingsWalletButton.requestFocus();
    }

    protected String getBtcTradeAmountLabel() {
        return Res.get("portfolio.pending.step5_buyer.bought");
    }

    protected String getFiatTradeAmountLabel() {
        return Res.get("portfolio.pending.step5_buyer.paid");
    }
}