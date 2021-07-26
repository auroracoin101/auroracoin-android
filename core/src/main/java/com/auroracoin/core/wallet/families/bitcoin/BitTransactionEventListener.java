package com.auroracoin.core.wallet.families.bitcoin;

import com.auroracoin.core.network.AddressStatus;
import com.auroracoin.core.network.ServerClient.UnspentTx;
import com.auroracoin.core.network.interfaces.TransactionEventListener;

import java.util.List;

/**
 * @author John L. Jegutanis
 */
public interface BitTransactionEventListener extends TransactionEventListener<BitTransaction> {
    void onUnspentTransactionUpdate(AddressStatus status, List<UnspentTx> UnspentTxes);
}
