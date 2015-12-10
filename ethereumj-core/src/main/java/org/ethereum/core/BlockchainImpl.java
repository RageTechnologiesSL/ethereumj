package org.ethereum.core;

import org.ethereum.config.SystemProperties;
import org.ethereum.crypto.HashUtil;
import org.ethereum.db.BlockStore;
import org.ethereum.listener.EthereumListener;
import org.ethereum.manager.AdminInfo;
import org.ethereum.mine.Miner;
import org.ethereum.trie.Trie;
import org.ethereum.trie.TrieImpl;
import org.ethereum.util.AdvancedDeviceUtils;
import org.ethereum.util.RLP;
import org.ethereum.validator.DependentBlockHeaderRule;
import org.ethereum.vm.program.invoke.ProgramInvokeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static java.lang.Runtime.getRuntime;
import static java.math.BigInteger.ZERO;
import static org.ethereum.config.Constants.*;
import static org.ethereum.config.SystemProperties.CONFIG;
import static org.ethereum.core.Denomination.SZABO;
import static org.ethereum.core.ImportResult.*;
import static org.ethereum.util.BIUtil.isMoreThan;

/**
 * The Ethereum blockchain is in many ways similar to the Bitcoin blockchain,
 * although it does have some differences.
 * <p>
 * The main difference between Ethereum and Bitcoin with regard to the blockchain architecture
 * is that, unlike Bitcoin, Ethereum blocks contain a copy of both the transaction list
 * and the most recent state. Aside from that, two other values, the block number and
 * the difficulty, are also stored in the block.
 * </p>
 * The block validation algorithm in Ethereum is as follows:
 * <ol>
 * <li>Check if the previous block referenced exists and is valid.</li>
 * <li>Check that the timestamp of the block is greater than that of the referenced previous block and less than 15 minutes into the future</li>
 * <li>Check that the block number, difficulty, transaction root, uncle root and gas limit (various low-level Ethereum-specific concepts) are valid.</li>
 * <li>Check that the proof of work on the block is valid.</li>
 * <li>Let S[0] be the STATE_ROOT of the previous block.</li>
 * <li>Let TX be the block's transaction list, with n transactions.
 * For all in in 0...n-1, set S[i+1] = APPLY(S[i],TX[i]).
 * If any applications returns an error, or if the total gas consumed in the block
 * up until this point exceeds the GASLIMIT, return an error.</li>
 * <li>Let S_FINAL be S[n], but adding the block reward paid to the miner.</li>
 * <li>Check if S_FINAL is the same as the STATE_ROOT. If it is, the block is valid; otherwise, it is not valid.</li>
 * </ol>
 * See <a href="https://github.com/ethereum/wiki/wiki/White-Paper#blockchain-and-mining">Ethereum Whitepaper</a>
 *
 * @author Roman Mandeleil
 * @author Nick Savers
 * @since 20.05.2014
 */
@Component
public class BlockchainImpl implements Blockchain, org.ethereum.facade.Blockchain {


    private static final Logger logger = LoggerFactory.getLogger("blockchain");
    private static final Logger stateLogger = LoggerFactory.getLogger("state");

    // to avoid using minGasPrice=0 from Genesis for the wallet
    private static final long INITIAL_MIN_GAS_PRICE = 10 * SZABO.longValue();

    @Autowired
    private Repository repository;
    private Repository track;

    @Autowired
    private BlockStore blockStore;

    private Block bestBlock;
    private BigInteger totalDifficulty = ZERO;

    @Autowired
    Wallet wallet;

    @Autowired
    private EthereumListener listener;

    @Autowired
    ProgramInvokeFactory programInvokeFactory;

    @Autowired
    private AdminInfo adminInfo;

    @Autowired
    private DependentBlockHeaderRule parentHeaderValidator;

    @Autowired
    private PendingState pendingState;

    private List<Chain> altChains = new ArrayList<>();
    private List<Block> garbage = new ArrayList<>();

    long exitOn = Long.MAX_VALUE;

    public boolean byTest = false;
    private boolean fork = false;

    public BlockchainImpl() {
    }


    //todo: autowire over constructor
    public BlockchainImpl(BlockStore blockStore, Repository repository,
                          Wallet wallet, AdminInfo adminInfo,
                          EthereumListener listener) {
        this.blockStore = blockStore;
        this.repository = repository;
        this.wallet = wallet;
        this.adminInfo = adminInfo;
        this.listener = listener;
    }

    @Override
    public byte[] getBestBlockHash() {
        return getBestBlock().getHash();
    }

    @Override
    public long getSize() {
        return bestBlock.getNumber() + 1;
    }

    @Override
    public Block getBlockByNumber(long blockNr) {
        return blockStore.getChainBlockByNumber(blockNr);
    }

    @Override
    public TransactionReceipt getTransactionReceiptByHash(byte[] hash) {
        throw new UnsupportedOperationException("TODO: will be implemented soon "); // FIXME: go and fix me
    }

    @Override
    public Block getBlockByHash(byte[] hash) {
        return blockStore.getBlockByHash(hash);
    }

    @Override
    public List<byte[]> getListOfHashesStartFrom(byte[] hash, int qty) {
        return blockStore.getListHashesEndWith(hash, qty);
    }

    @Override
    public List<byte[]> getListOfHashesStartFromBlock(long blockNumber, int qty) {
        long bestNumber = bestBlock.getNumber();

        if (blockNumber > bestNumber) {
            return Collections.emptyList();
        }

        if (blockNumber + qty - 1 > bestNumber) {
            qty = (int) (bestNumber - blockNumber + 1);
        }

        long endNumber = blockNumber + qty - 1;

        Block block = getBlockByNumber(endNumber);

        List<byte[]> hashes = blockStore.getListHashesEndWith(block.getHash(), qty);

        // asc order of hashes is required in the response
        Collections.reverse(hashes);

        return hashes;
    }

    private byte[] calcTxTrie(List<Transaction> transactions) {

        Trie txsState = new TrieImpl(null);

        if (transactions == null || transactions.isEmpty())
            return HashUtil.EMPTY_TRIE_HASH;

        for (int i = 0; i < transactions.size(); i++) {
            txsState.update(RLP.encodeInt(i), transactions.get(i).getEncoded());
        }
        return txsState.getRootHash();
    }

    public ImportResult tryConnectAndFork(Block block) {
        return tryConnectAndFork(block, false);
    }

    public ImportResult tryConnectAndFork(Block block, boolean justMined) {

        Repository savedRepo = this.repository;
        Block savedBest = this.bestBlock;
        BigInteger savedTD = this.totalDifficulty;

        this.bestBlock = blockStore.getBlockByHash(block.getParentHash());
        totalDifficulty = blockStore.getTotalDifficultyForHash(block.getParentHash());
        this.repository = this.repository.getSnapshotTo(this.bestBlock.getStateRoot());
        this.fork = true;

        try {

            // FIXME: adding block with no option for flush
            add(block, null);
        } catch (Throwable th) {
            logger.error("Unexpected error: ", th);
        } finally {this.fork = false;}

        if (isMoreThan(this.totalDifficulty, savedTD)) {

            logger.info("Rebranching: {} ~> {}", savedBest.getShortHash(), block.getShortHash());

            // main branch become this branch
            // cause we proved that total difficulty
            // is greateer
            blockStore.reBranch(block);

            // The main repository rebranch
            this.repository = savedRepo;
            this.repository.syncToRoot(block.getStateRoot());

            // flushing
            if (!byTest){
                repository.flush();
                blockStore.flush();
                System.gc();
            }

            return IMPORTED_BEST;
        } else {

            // Stay on previous branch
            this.repository = savedRepo;
            this.bestBlock = savedBest;
            this.totalDifficulty = savedTD;

            return IMPORTED_NOT_BEST;
        }
    }


    public ImportResult tryToConnect(Block block) {

        if (logger.isInfoEnabled())
            logger.info("Try connect block hash: {}, number: {}",
                    Hex.toHexString(block.getHash()).substring(0, 6),
                    block.getNumber());

        if (blockStore.getMaxNumber() >= block.getNumber() &&
                blockStore.isBlockExist(block.getHash())) {

            if (logger.isDebugEnabled())
                logger.debug("Block already exist hash: {}, number: {}",
                        Hex.toHexString(block.getHash()).substring(0, 6),
                        block.getNumber());

            // retry of well known block
            return EXIST;
        }

        // The simple case got the block
        // to connect to the main chain
        if (bestBlock.isParentOf(block)) {
            recordBlock(block);
            add(block);

            pendingState.processBest(block);

            return IMPORTED_BEST;
        } else {

            if (blockStore.isBlockExist(block.getParentHash())) {
                recordBlock(block);
                ImportResult result = tryConnectAndFork(block);

                if (result == IMPORTED_BEST) pendingState.processBest(block);

                return result;
            }

        }

        return NO_PARENT;
    }


    @Override
    public void add(Block block) {
        add(block, null);
    }

    public void add(Block block, Miner miner) {

        if (exitOn < block.getNumber()) {
            System.out.print("Exiting after block.number: " + getBestBlock().getNumber());
            System.exit(-1);
        }


        if (!isValid(block)) {
            logger.warn("Invalid block with number: {}", block.getNumber());
            return;
        }

        track = repository.startTracking();
        if (block == null)
            return;

        // keep chain continuity
        if (!Arrays.equals(getBestBlock().getHash(),
                block.getParentHash())) return;

        if (block.getNumber() >= CONFIG.traceStartBlock() && CONFIG.traceStartBlock() != -1) {
            AdvancedDeviceUtils.adjustDetailedTracing(block.getNumber());
        }

        List<TransactionReceipt> receipts = processBlock(block);

        // Sanity checks
        String receiptHash = Hex.toHexString(block.getReceiptsRoot());
        String receiptListHash = Hex.toHexString(calcReceiptsTrie(receipts));

        if (!receiptHash.equals(receiptListHash)) {
            logger.error("Block's given Receipt Hash doesn't match: {} != {}", receiptHash, receiptListHash);
            //return false;
        }

        String logBloomHash = Hex.toHexString(block.getLogBloom());
        String logBloomListHash = Hex.toHexString(calcLogBloom(receipts));

        if (!logBloomHash.equals(logBloomListHash)) {
            logger.error("Block's given logBloom Hash doesn't match: {} != {}", logBloomHash, logBloomListHash);
            //track.rollback();
            //return;
        }

        //DEBUG
        //System.out.println(" Receipts root is: " + receiptHash + " logbloomhash is " + logBloomHash);
        //System.out.println(" Receipts listroot is: " + receiptListHash + " logbloomlisthash is " + logBloomListHash);

        track.commit();

        if (miner != null) {
            logger.info("Updating stateRoot for block #{} and mining it...", block.getNumber());
            block.getHeader().setStateRoot(repository.getRoot());
            miner.mine(block.getHeader());
            logger.info("Block #{} mined!", block.getNumber());
        }

        storeBlock(block, receipts);


        if (!byTest && needFlush(block)) {
            repository.flush();
            blockStore.flush();
            System.gc();
        }

        // Remove all wallet transactions as they already approved by the net
        wallet.removeTransactions(block.getTransactionsList());

        listener.trace(String.format("Block chain size: [ %d ]", this.getSize()));
        listener.onBlock(block, receipts);
    }

    private boolean needFlush(Block block) {
        if (CONFIG.cacheFlushMemory() > 0) {
            return needFlushByMemory(CONFIG.cacheFlushMemory());
        } else if (CONFIG.cacheFlushBlocks() > 0) {
            return block.getNumber() % CONFIG.cacheFlushBlocks() == 0;
        } else {
            return needFlushByMemory(.7);
        }
    }

    private boolean needFlushByMemory(double maxMemoryPercents) {
        return getRuntime().freeMemory() < (getRuntime().totalMemory() * (1 - maxMemoryPercents));
    }

    private byte[] calcReceiptsTrie(List<TransactionReceipt> receipts) {
        //TODO Fix Trie hash for receipts - doesnt match cpp
        Trie receiptsTrie = new TrieImpl(null);

        if (receipts == null || receipts.isEmpty())
            return HashUtil.EMPTY_TRIE_HASH;

        for (int i = 0; i < receipts.size(); i++) {
            receiptsTrie.update(RLP.encodeInt(i), receipts.get(i).getEncoded());
        }
        return receiptsTrie.getRootHash();
    }

    private byte[] calcLogBloom(List<TransactionReceipt> receipts) {

        Bloom retBloomFilter = new Bloom();

        if (receipts == null || receipts.isEmpty())
            return retBloomFilter.getData();

        for (int i = 0; i < receipts.size(); i++) {
            retBloomFilter.or(receipts.get(i).getBloomFilter());
        }

        return retBloomFilter.getData();
    }

    public Block getParent(BlockHeader header) {

        return blockStore.getBlockByHash(header.getParentHash());
    }


    public boolean isValid(BlockHeader header) {

        Block parentBlock = getParent(header);

        if (!parentHeaderValidator.validate(header, parentBlock.getHeader())) {

            if (logger.isErrorEnabled())
                parentHeaderValidator.logErrors(logger);

            return false;
        }

        return true;
    }

    /**
     * This mechanism enforces a homeostasis in terms of the time between blocks;
     * a smaller period between the last two blocks results in an increase in the
     * difficulty level and thus additional computation required, lengthening the
     * likely next period. Conversely, if the period is too large, the difficulty,
     * and expected time to the next block, is reduced.
     */
    private boolean isValid(Block block) {

        boolean isValid = true;

        if (!block.isGenesis()) {
            isValid = isValid(block.getHeader());

            // Sanity checks
            String trieHash = Hex.toHexString(block.getTxTrieRoot());
            String trieListHash = Hex.toHexString(calcTxTrie(block.getTransactionsList()));


            if( !trieHash.equals(trieListHash) ) {
              logger.error("Block's given Trie Hash doesn't match: {} != {}", trieHash, trieListHash);

              //   FIXME: temporary comment out tx.trie validation
//              return false;
            }


            String unclesHash = Hex.toHexString(block.getHeader().getUnclesHash());
            String unclesListHash = Hex.toHexString(HashUtil.sha3(block.getHeader().getUnclesEncoded(block.getUncleList())));

            if (!unclesHash.equals(unclesListHash)) {
                logger.error("Block's given Uncle Hash doesn't match: {} != {}", unclesHash, unclesListHash);
                return false;
            }


            if (block.getUncleList().size() > UNCLE_LIST_LIMIT) {
                logger.error("Uncle list to big: block.getUncleList().size() > UNCLE_LIST_LIMIT");
                return false;
            }


            for (BlockHeader uncle : block.getUncleList()) {

                // - They are valid headers (not necessarily valid blocks)
                if (!isValid(uncle)) return false;

                //if uncle's parent's number is not less than currentBlock - UNCLE_GEN_LIMIT, mark invalid
                isValid = !(getParent(uncle).getNumber() < (block.getNumber() - UNCLE_GENERATION_LIMIT));
                if (!isValid) {
                    logger.error("Uncle too old: generationGap must be under UNCLE_GENERATION_LIMIT");
                    return false;
                }


            }
        }

        return isValid;
    }

    private List<TransactionReceipt> processBlock(Block block) {

        List<TransactionReceipt> receipts = new ArrayList<>();
        if (!block.isGenesis()) {
            if (!CONFIG.blockChainOnly()) {
                wallet.addTransactions(block.getTransactionsList());
                receipts = applyBlock(block);
                wallet.processBlock(block);
            }
        }

        return receipts;
    }

    public List<TransactionReceipt> applyBlock(Block block) {

        logger.info("applyBlock: block: [{}] tx.list: [{}]", block.getNumber(), block.getTransactionsList().size());
        long saveTime = System.nanoTime();
        int i = 1;
        long totalGasUsed = 0;
        List<TransactionReceipt> receipts = new ArrayList<>();

        for (Transaction tx : block.getTransactionsList()) {
            stateLogger.info("apply block: [{}] tx: [{}] ", block.getNumber(), i);

            TransactionExecutor executor = new TransactionExecutor(tx, block.getCoinbase(),
                    track, blockStore,
                    programInvokeFactory, block, listener, totalGasUsed);

            executor.init();
            executor.execute();
            executor.go();
            executor.finalization();

            totalGasUsed += executor.getGasUsed();

            track.commit();
            TransactionReceipt receipt = new TransactionReceipt();
            receipt.setCumulativeGas(totalGasUsed);
            receipt.setPostTxState(repository.getRoot());
            receipt.setTransaction(tx);
            receipt.setLogInfoList(executor.getVMLogs());

            stateLogger.info("block: [{}] executed tx: [{}] \n  state: [{}]", block.getNumber(), i,
                    Hex.toHexString(repository.getRoot()));

            stateLogger.info("[{}] ", receipt.toString());

            if (stateLogger.isInfoEnabled())
                stateLogger.info("tx[{}].receipt: [{}] ", i, Hex.toHexString(receipt.getEncoded()));

            if (block.getNumber() >= CONFIG.traceStartBlock())
                repository.dumpState(block, totalGasUsed, i++, tx.getHash());

            receipts.add(receipt);
        }

        addReward(block);
        updateTotalDifficulty(block);

        track.commit();

        stateLogger.info("applied reward for block: [{}]  \n  state: [{}]",
                block.getNumber(),
                Hex.toHexString(repository.getRoot()));


        if (block.getNumber() >= CONFIG.traceStartBlock())
            repository.dumpState(block, totalGasUsed, 0, null);

        long totalTime = System.nanoTime() - saveTime;
        adminInfo.addBlockExecTime(totalTime);
        logger.info("block: num: [{}] hash: [{}], executed after: [{}]nano", block.getNumber(), block.getShortHash(), totalTime);

        return receipts;
    }

    /**
     * Add reward to block- and every uncle coinbase
     * assuming the entire block is valid.
     *
     * @param block object containing the header and uncles
     */
    private void addReward(Block block) {

        // Add standard block reward
        BigInteger totalBlockReward = Block.BLOCK_REWARD;

        // Add extra rewards based on number of uncles
        if (block.getUncleList().size() > 0) {
            for (BlockHeader uncle : block.getUncleList()) {
                track.addBalance(uncle.getCoinbase(),
                        new BigDecimal(block.BLOCK_REWARD).multiply(BigDecimal.valueOf(8 + uncle.getNumber() - block.getNumber()).divide(new BigDecimal(8))).toBigInteger());

                totalBlockReward = totalBlockReward.add(Block.INCLUSION_REWARD);
            }
        }
        track.addBalance(block.getCoinbase(), totalBlockReward);


    }

    @Override
    public void storeBlock(Block block, List<TransactionReceipt> receipts) {

        /* Debug check to see if the state is still as expected */
        String blockStateRootHash = Hex.toHexString(block.getStateRoot());
        String worldStateRootHash = Hex.toHexString(repository.getRoot());

        if (!SystemProperties.CONFIG.blockChainOnly())
            if (!blockStateRootHash.equals(worldStateRootHash)) {

                stateLogger.error("BLOCK: STATE CONFLICT! block: {} worldstate {} mismatch", block.getNumber(), worldStateRootHash);
//                stateLogger.error("DO ROLLBACK !!!");
                adminInfo.lostConsensus();

                System.out.println("CONFLICT: BLOCK #" + block.getNumber());
                System.exit(1);
                // in case of rollback hard move the root
//                Block parentBlock = blockStore.getBlockByHash(block.getParentHash());
//                repository.syncToRoot(parentBlock.getStateRoot());
//                return false;
            }

        if (fork)
            blockStore.saveBlock(block, totalDifficulty, false);
        else
            blockStore.saveBlock(block, totalDifficulty, true);

        logger.info("Block saved: number: {}, hash: {}, TD: {}",
                block.getNumber(), block.getShortHash(), totalDifficulty);

        setBestBlock(block);

        if (logger.isDebugEnabled())
            logger.debug("block added to the blockChain: index: [{}]", block.getNumber());
        if (block.getNumber() % 100 == 0)
            logger.info("*** Last block added [ #{} ]", block.getNumber());

    }


    public boolean hasParentOnTheChain(Block block) {
        return getParent(block.getHeader()) != null;
    }

    @Override
    public List<Chain> getAltChains() {
        return altChains;
    }

    @Override
    public List<Block> getGarbage() {
        return garbage;
    }

    @Override
    public void setBestBlock(Block block) {
        bestBlock = block;
    }

    @Override
    public Block getBestBlock() {
        return bestBlock;
    }

    @Override
    public void close() {
    }

    @Override
    public BigInteger getTotalDifficulty() {
        return totalDifficulty;
    }

    @Override
    public void updateTotalDifficulty(Block block) {
        totalDifficulty = totalDifficulty.add(block.getDifficultyBI());
        logger.info("TD: updated to {}" , totalDifficulty);
    }

    @Override
    public void setTotalDifficulty(BigInteger totalDifficulty) {
        this.totalDifficulty = totalDifficulty;
    }

    private void recordBlock(Block block) {

        if (!CONFIG.recordBlocks()) return;

        String dumpDir = CONFIG.databaseDir() + "/" + CONFIG.dumpDir();

        File dumpFile = new File(dumpDir + "/blocks-rec.dmp");
        FileWriter fw = null;
        BufferedWriter bw = null;

        try {

            dumpFile.getParentFile().mkdirs();
            if (!dumpFile.exists()) dumpFile.createNewFile();

            fw = new FileWriter(dumpFile.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);

            if (bestBlock.isGenesis()) {
                bw.write(Hex.toHexString(bestBlock.getEncoded()));
                bw.write("\n");
            }

            bw.write(Hex.toHexString(block.getEncoded()));
            bw.write("\n");

        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (bw != null) bw.close();
                if (fw != null) fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setProgramInvokeFactory(ProgramInvokeFactory factory) {
        this.programInvokeFactory = factory;
    }

    public void startTracking() {
        track = repository.startTracking();
    }

    public void commitTracking() {
        track.commit();
    }

    public void setExitOn(long exitOn) {
        this.exitOn = exitOn;
    }

    public boolean isBlockExist(byte[] hash) {
        return blockStore.isBlockExist(hash);
    }

    public void setParentHeaderValidator(DependentBlockHeaderRule parentHeaderValidator) {
        this.parentHeaderValidator = parentHeaderValidator;
    }

    public void setPendingState(PendingState pendingState) {
        this.pendingState = pendingState;
    }

    public PendingState getPendingState() {
        return pendingState;
    }

    public BlockStore getBlockStore() {
        return blockStore;
    }

    public ProgramInvokeFactory getProgramInvokeFactory() {
        return programInvokeFactory;
    }

    @Override
    public List<BlockHeader> getListOfHeadersStartFrom(BlockIdentifier identifier, int skip, int limit, boolean reverse) {
        long blockNumber = identifier.getNumber();

        if (identifier.getHash() != null) {
            Block block = getBlockByHash(identifier.getHash());

            if (block == null) {
                return Collections.emptyList();
            }

            blockNumber = block.getNumber();
        }

        long bestNumber = bestBlock.getNumber();

        int qty = getQty(blockNumber, bestNumber, limit, reverse);

        byte[] startHash = getStartHash(blockNumber, skip, qty, reverse);

        if (startHash == null) {
            return Collections.emptyList();
        }

        List<BlockHeader> headers = blockStore.getListHeadersEndWith(startHash, qty);

        // blocks come with falling numbers
        if (!reverse) {
            Collections.reverse(headers);
        }

        return headers;
    }

    private int getQty(long blockNumber, long bestNumber, int limit, boolean reverse) {
        if (reverse) {
            return blockNumber - limit + 1 < 0 ? (int) (blockNumber + 1) : limit;
        } else {
            if (blockNumber + limit - 1 > bestNumber) {
                return (int) (bestNumber - blockNumber + 1);
            } else {
                return limit;
            }
        }
    }

    private byte[] getStartHash(long blockNumber, int skip, int qty, boolean reverse) {

        long startNumber;

        if (reverse) {
            startNumber = blockNumber - skip;
        } else {
            startNumber = blockNumber + skip + qty - 1;
        }

        Block block = getBlockByNumber(startNumber);

        if (block == null) {
            return null;
        }

        return block.getHash();
    }

    @Override
    public List<byte[]> getListOfBodiesByHashes(List<byte[]> hashes) {
        List<byte[]> bodies = new ArrayList<>(hashes.size());

        for (byte[] hash : hashes) {
            Block block = blockStore.getBlockByHash(hash);
            if (block == null) break;
            bodies.add(block.getEncodedBody());
        }

        return bodies;
    }
}
