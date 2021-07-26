package com.auroracoin.core.wallet.families.bitcoin;

import com.auroracoin.core.network.AddressStatus;
import com.auroracoin.core.network.interfaces.BlockchainConnection;

/**
 * @author John L. Jegutanis
 */
public interface BitBlockchainConnection extends BlockchainConnection<BitTransaction> {
    void getUnspentTx(AddressStatus status, BitTransactionEventListener listener);
}
