package org.ethereum.mine;

import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.core.BlockHeader;
import org.ethereum.util.ByteUtil;
import org.ethereum.util.FastByteComparisons;

import static org.ethereum.crypto.HashUtil.sha3;
import static org.ethereum.util.ByteUtil.intToBytes;
import static org.ethereum.util.ByteUtil.intToBytesNoLeadZeroes;

/**
 * More high level validator/miner class which keeps a cache for the last requested block epoch
 *
 * Created by Anton Nashatyrev on 04.12.2015.
 */
public class Ethash implements Miner {
    private static EthashParams ethashParams = new EthashParams();

    private static Ethash cachedInstance = null;
    private static long cachedBlockEpoch = 0;

    /**
     * Returns instance for the specified block number either from cache or calculates a new one
     */
    public static Ethash getForBlock(long blockNumber) {
        long epoch = blockNumber / ethashParams.getEPOCH_LENGTH();
        if (cachedInstance == null || epoch != cachedBlockEpoch) {
            cachedInstance = new Ethash(blockNumber);
            cachedBlockEpoch = epoch;
        }
        return cachedInstance;
    }

    private EthashAlgo ethashAlgo = new EthashAlgo(ethashParams);

    private long blockNumber;
    private byte[][] cacheLight = null;
    private byte[][] fullData = null;

    public Ethash(long blockNumber) {
        this.blockNumber = blockNumber;
    }

    private byte[][] getCacheLight() {
        if (cacheLight == null) {
            cacheLight = getEthashAlgo().makeCache(getEthashAlgo().getParams().getCacheSize(blockNumber),
                    getEthashAlgo().getSeedHash(blockNumber));
        }
        return cacheLight;
    }

    private byte[][] getFullDataset() {
        if (fullData == null) {
            fullData = getEthashAlgo().calcDataset(getFullSize(), getCacheLight());
        }
        return fullData;
    }

    private long getFullSize() {
        return getEthashAlgo().getParams().getFullSize(blockNumber);
    }

    private EthashAlgo getEthashAlgo() {
        return ethashAlgo;
    }

    /**
     *  See {@link EthashAlgo#hashimotoLight(long, byte[][], byte[], byte[])}
     */
    public Pair<byte[], byte[]> hashimotoLight(BlockHeader header, long nonce) {
        return hashimotoLight(header, intToBytes((int) nonce));
    }

    private  Pair<byte[], byte[]> hashimotoLight(BlockHeader header, byte[] nonce) {
        return getEthashAlgo().hashimotoLight(getFullSize(), getCacheLight(),
                sha3(header.getEncodedWithoutNonce()), nonce);
    }

    /**
     *  See {@link EthashAlgo#hashimotoFull(long, byte[][], byte[], byte[])}
     */
    public Pair<byte[], byte[]> hashimotoFull(BlockHeader header, long nonce) {
        return getEthashAlgo().hashimotoFull(getFullSize(), getFullDataset(), sha3(header.getEncodedWithoutNonce()),
                intToBytes((int) nonce));
    }

    /**
     *  Mines the nonce for the specified BlockHeader with difficulty BlockHeader.getDifficulty()
     *  When mined the BlockHeader 'nonce' and 'mixHash' fields are updated
     *  Uses the full dataset i.e. it faster but takes > 1Gb of memory
     *  @return mined nonce
     */
    public long mine(BlockHeader header) {
        long nonce = getEthashAlgo().mine(getFullSize(), getFullDataset(), sha3(header.getEncodedWithoutNonce()),
                ByteUtil.byteArrayToLong(header.getDifficulty()));
        Pair<byte[], byte[]> pair = hashimotoLight(header, nonce);
        header.setNonce(intToBytesNoLeadZeroes((int) nonce));
        header.setMixHash(pair.getLeft());
        return nonce;
    }

    /**
     *  Mines the nonce for the specified BlockHeader with difficulty BlockHeader.getDifficulty()
     *  When mined the BlockHeader 'nonce' and 'mixHash' fields are updated
     *  Uses the light cache i.e. it slower but takes only ~16Mb of memory
     *  @return mined nonce
     */
    public long mineLight(BlockHeader header) {
        long nonce = getEthashAlgo().mineLight(getFullSize(), getCacheLight(), sha3(header.getEncodedWithoutNonce()),
                ByteUtil.byteArrayToLong(header.getDifficulty()));
        Pair<byte[], byte[]> pair = hashimotoLight(header, nonce);
        header.setNonce(intToBytesNoLeadZeroes((int) nonce));
        header.setMixHash(pair.getLeft());
        return nonce;
    }

    /**
     *  Validates the BlockHeader against its getDifficulty() and getNonce()
     */
    public boolean validate(BlockHeader header) {
        byte[] boundary = header.getPowBoundary();
        byte[] hash = hashimotoLight(header, header.getNonce()).getRight();

        return FastByteComparisons.compareTo(hash, 0, 32, boundary, 0, 32) < 0;
    }
}
