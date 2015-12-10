package org.ethereum.mine;

import org.ethereum.core.BlockHeader;

/**
 * Created by Anton Nashatyrev on 08.12.2015.
 */
public interface Miner {

    long mine(BlockHeader header);

    boolean validate(BlockHeader header);
}
