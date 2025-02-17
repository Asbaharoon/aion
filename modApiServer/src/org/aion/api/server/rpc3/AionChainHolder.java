package org.aion.api.server.rpc3;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.aion.api.server.account.Account;
import org.aion.api.server.account.AccountManagerInterface;
import org.aion.base.AccountState;
import org.aion.crypto.ECKey;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.zero.impl.core.IRewardsCalculator;
import org.aion.zero.impl.types.Block;
import org.aion.zero.impl.types.BlockHeader.Seal;
import org.aion.types.AionAddress;
import org.aion.util.bytes.ByteUtil;
import org.aion.zero.impl.blockchain.AionBlockchainImpl;
import org.aion.zero.impl.blockchain.AionImpl;
import org.aion.zero.impl.blockchain.IAionChain;
import org.aion.zero.impl.core.ImportResult;
import org.aion.zero.impl.keystore.Keystore;
import org.aion.zero.impl.types.MiningBlock;
import org.aion.zero.impl.types.AionTxInfo;
import org.aion.zero.impl.types.BlockContext;
import org.aion.zero.impl.types.StakingBlock;
import org.aion.zero.impl.valid.FutureBlockRule;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

public class AionChainHolder implements ChainHolder {

    private final IAionChain chain;//An implementation of AionChain
    private final AtomicReference<BlockContext> currentTemplate;
    private final AccountManagerInterface accountManager;
    private final FutureBlockRule futureBlockRule;
    private final Logger logger;
    private final ScheduledExecutorService blockSubmitExecutor;
    private final AtomicReference<Pair<Long, StakingBlock>> stakingBlockTemplate;
    private final static long templateRefreshRate = 2000; //ms


    public AionChainHolder(IAionChain chain,
        AccountManagerInterface accountManager) {
        if (chain == null) {
            throw new NullPointerException("AionChain is null.");// This class should not
            // be instantiated without an instance of IAionChain
        }
        if (accountManager == null) {
            throw new NullPointerException("AccountManager is null.");// This class should not
            // be instantiated without an instance of AccountManager
        }
        this.chain = chain;
        currentTemplate = new AtomicReference<>(null);
        this.accountManager = accountManager;
        this.futureBlockRule = new FutureBlockRule();
        logger = AionLoggerFactory.getLogger(LogEnum.CONS.name());
        blockSubmitExecutor = Executors.newSingleThreadScheduledExecutor();
        stakingBlockTemplate = new AtomicReference<>(null);
    }

    @Override
    public Block getBlockByNumber(long block) {
        return this.chain.getAionHub().getBlockchain().getBlockByNumber(block);
    }

    @Override
    public Block getBestBlock() {
        return this.chain.getAionHub().getBlockchain().getBestBlock();
    }

    @Override
    public Block getBlockByHash(byte[] hash) {
        return this.chain.getAionHub().getBlockchain().getBlockByHash(hash);
    }

    @Override
    public BigInteger getTotalDifficultyByHash(byte[] hash) {
        return this.chain.getAionHub().getTotalDifficultyForHash(hash);
    }

    @Override
    public AionTxInfo getTransactionInfo(byte[] transactionHash) {
        return this.chain.getAionHub().getBlockchain().getTransactionInfo(transactionHash);
    }

    @Override
    public BigInteger calculateReward(Block block) {
        Objects.requireNonNull(block);

        if (chain.getAionHub().isForkSignatureSwapActive(block.getNumber())) {
            boolean isMiningBlock = block.getHeader().getSealType().equals(Seal.PROOF_OF_WORK);
            IRewardsCalculator calculator =
                ((AionBlockchainImpl)chain.getBlockchain()).getChainConfiguration().getRewardsCalculatorAfterSignatureSchemeSwap(isMiningBlock);
            if (isMiningBlock) {
                Block parent = chain.getBlockchain().getBlockByHash(block.getParentHash());
                Objects.requireNonNull(parent);

                return calculator.calculateReward(block.getTimestamp() - parent.getTimestamp());
            } else {
                return calculator.calculateReward(block.getNumber());
            }
        } else {
            return ((AionBlockchainImpl)chain.getBlockchain())
                .getChainConfiguration()
                .getRewardsCalculatorBeforeSignatureSchemeSwap(chain.getAionHub().isForkUnityActive(block.getNumber()))
                .calculateReward(block.getNumber());
        }
    }

    @Override
    public boolean isUnityForkEnabled() {
        return this.chain.getAionHub().getBlockchain().isUnityForkEnabledAtNextBlock();
    }

    @Override
    public boolean submitSignature(byte[] signature, byte[] sealHash) {
        if (!isUnityForkEnabled()) throw new UnsupportedOperationException();
        else {
            StakingBlock stakingBlock = chain.getBlockchain().getCachingStakingBlockTemplate(sealHash);

            logger.debug(
                    "submitSignature: sig[{}], sealHash[{}], block[{}]",
                    ByteUtil.toHexString(signature),
                    ByteUtil.toHexString(sealHash),
                    stakingBlock);

            if (!stakingBlock.isSealed()
                    && Arrays.equals(sealHash, stakingBlock.getHeader().getMineHash())) {
                stakingBlock.seal(signature, stakingBlock.getHeader().getSigningPublicKey());

                boolean result =
                        addSealedBlockToPool(
                            stakingBlock,
                            TimeUnit.SECONDS.toMillis(stakingBlock.getTimestamp())
                                    - System.currentTimeMillis());
                logSealedBlock(stakingBlock);
                return result;
            } else {
                logFailedSealedBlock(stakingBlock);
                return false;
            }
        }
    }

    /* package private for testing purpose) */
    boolean addSealedBlockToPool(StakingBlock stakingBlock, long period) {
        if (period > 0) {
            // We are not using the future to block the tasks, So just return true directly
            scheduleTask(stakingBlock, period);
            return true;
        } else {
            return addNewBlock(stakingBlock);
        }
    }

    /* package private for testing purpose) */
    void scheduleTask(StakingBlock stakingBlock, long period) {
        blockSubmitExecutor.schedule(
            () -> addNewBlock(stakingBlock), period, TimeUnit.MILLISECONDS);
    }

    @Override
    public byte[] submitSeed(byte[] newSeed, byte[] signingPublicKey, byte[] coinBase) {
        if (!isUnityForkEnabled()) throw new UnsupportedOperationException();
        else {
            Pair<Long, StakingBlock> template = stakingBlockTemplate.get();
            StakingBlock blockTemplate = template == null ? null : template.getRight();
            if (blockTemplate != null
                && Arrays.equals(blockTemplate.getSeed(), newSeed)
                && Arrays.equals(blockTemplate.getHeader().getSigningPublicKey(), signingPublicKey)
                && blockTemplate.getCoinbase().equals(new AionAddress(coinBase))
                && System.currentTimeMillis() <= template.getLeft() + templateRefreshRate) {
                return blockTemplate.getHeader().getMineHash();
            } else {
                blockTemplate = this.chain.getStakingBlockTemplate(newSeed, signingPublicKey, coinBase);
                stakingBlockTemplate.set(Pair.of(System.currentTimeMillis(), blockTemplate));

                if (blockTemplate == null) {
                    return ByteUtil.EMPTY_BYTE_ARRAY;
                } else {
                    return blockTemplate.getHeader().getMineHash();
                }
            }
        }
    }

    @Override
    public byte[] getSeed() {
        if (!isUnityForkEnabled()) throw new UnsupportedOperationException();
        else return this.chain.getBlockchain().getSeed();
    }

    @Override
    public synchronized BlockContext getBlockTemplate() {
        return currentTemplate.updateAndGet(bc -> this.chain.getNewMiningBlockTemplate(bc,
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())));
    }

    @Override
    public boolean submitBlock(byte[] nonce, byte[] solution, byte[] headerHash) {
        MiningBlock bestPowBlock = this.chain.getBlockchain().getCachingMiningBlockTemplate(headerHash);
        if (bestPowBlock == null) {
            return false; // cannot seal a block that does not exist
        } else {
            bestPowBlock.seal(nonce, solution);

            //AKI-648 reject the block add into the kernel if the timestamp of the block is in the future.
            boolean isValidTimestamp = futureBlockRule.validate(bestPowBlock.getHeader(), new ArrayList<>());

            final boolean sealedSuccessfully = isValidTimestamp && addNewBlock(bestPowBlock);

            if (sealedSuccessfully) {
                logSealedBlock(bestPowBlock);
            } else {
               logFailedSealedBlock(bestPowBlock);
            }
            return sealedSuccessfully;
        }
    }
    
    @Override
    public boolean canSeal(byte[] headerHash) {
        return this.chain.getBlockchain().getCachingMiningBlockTemplate(headerHash) != null ||
            this.chain.getBlockchain().getCachingStakingBlockTemplate(headerHash) != null;
    }

    @Override
    public boolean addNewBlock(Block block) {
        ImportResult result = ((AionImpl) chain).addNewBlock(block);
        logger.info(
            "{} block {} to the blockchain DB <num={}, hash={}, diff={}, tx={}>",
            block.getHeader().getSealType() == Seal.PROOF_OF_WORK ? "mining" : "staking",
            result.isSuccessful() ? "sealed" : "cannot seal",
            block.getNumber(),
            block.getShortHash(),
            block.getDifficultyBI(),
            block.getTransactionsList().size());

        return result.isSuccessful();
    }

    @Override
    public BigInteger getAccountBalance(AionAddress aionAddress, long blockNumber) {
        Optional<AccountState> accountState = chain.getAccountState(aionAddress, blockNumber);
        if (accountState.isPresent()) {
            return accountState.get().getBalance();
        } else {
            return BigInteger.ZERO;
        }
    }

    @Override
    public BigInteger getAccountNonce(AionAddress aionAddress, long blockNumber) {
        Optional<AccountState> accountState = chain.getAccountState(aionAddress, blockNumber);
        if (accountState.isPresent()) {
            return accountState.get().getNonce();
        } else {
            return BigInteger.ZERO;
        }
    }

    @Override
    public BigInteger getPendingAccountNonce(AionAddress aionAddress) {
        return this.chain.getPendingState().getNonce(aionAddress);
    }

    @Override
    public BigInteger getAccountBalance(AionAddress aionAddress) {
        Optional<AccountState> accountState = chain.getAccountState(aionAddress);

        if (accountState.isPresent()) {
            return accountState.get().getBalance();
        } else {
            return BigInteger.ZERO;
        }
    }

    @Override
    public BigInteger getAccountNonce(AionAddress aionAddress) {
        Optional<AccountState> accountState = chain.getAccountState(aionAddress);
        if (accountState.isPresent()) {
            return accountState.get().getNonce();
        } else {
            return BigInteger.ZERO;
        }
    }

    @Override
    public AccountState getAccountState(AionAddress aionAddress) {
        Optional<AccountState> accountState = chain.getAccountState(aionAddress);
        return accountState.orElseGet(AccountState::new);
    }

    @Override
    public long blockNumber() {
        return this.getBestBlock().getNumber();
    }

    @Override
    public boolean unlockAccount(AionAddress aionAddress, String password, int timeout) {
        return accountManager.unlockAccount(aionAddress, password, timeout);
    }

    @Override
    public boolean lockAccount(AionAddress aionAddress, String password) {
        return accountManager.lockAccount(aionAddress, password);
    }

    @Override
    public AionAddress newAccount(String password) {
        return accountManager.createAccount(password);
    }

    @Override
    public List<AionAddress> listAccounts() {
        return accountManager.getAccounts().stream().map(Account::getKey).map(ECKey::getAddress)
            .map(AionAddress::new).collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void shutDown() {
        logger.info("rpcChainHolder shutting down.");
        blockSubmitExecutor.shutdownNow();
    }

    @Override
    public MiningBlock getBestPOWBlock() {
        return this.chain.getBlockchain().getBestMiningBlock();
    }

    @Override
    public StakingBlock getBestPOSBlock() {
        return this.chain.getBlockchain().getBestStakingBlock();
    }

    @Override
    public boolean addressExists(AionAddress address) {
        return Keystore.exist(address.toString());
    }

    private void logSealedBlock(Block block){
        //log that the block was sealed
        logger.info(
            "{} block submitted via api <num={}, hash={}, diff={}, tx={}>",
            block.getHeader().getSealType().equals(Seal.PROOF_OF_WORK) ? "Mining": "Staking",
            block.getNumber(),
            block.getShortHash(), // LogUtil.toHexF8(newBlock.getHash()),
            block.getHeader().getDifficultyBI().toString(),
            block.getTransactionsList().size());
    }

    private void logFailedSealedBlock(Block block){
        //log that the block could not be sealed
        logger.debug(
            "Unable to submit {} block via api <num={}, hash={}, diff={}, tx={}>",
            block.getHeader().getSealType().equals(Seal.PROOF_OF_WORK) ? "mining": "staking",
            block.getNumber(),
            block.getShortHash(), // LogUtil.toHexF8(newBlock.getHash()),
            block.getHeader().getDifficultyBI().toString(),
            block.getTransactionsList().size());
    }
}
