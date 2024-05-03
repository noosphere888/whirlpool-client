package com.samourai.whirlpool.client.wallet.data.utxoConfig;

import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import com.samourai.whirlpool.client.wallet.data.dataPersister.PersistableSupplier;
import java.util.Collection;

public interface UtxoConfigSupplier extends PersistableSupplier {
  UtxoConfig getUtxo(String utxoHash, int utxoIndex);

  void setMixsDone(String utxoHash, int utxoIndex, int mixsDone);

  void setBlocked(String utxoHash, int utxoIndex, boolean blocked);

  void setNote(String utxoHash, int utxoIndex, String note);

  void clean(Collection<WhirlpoolUtxo> existingUtxos);
}
