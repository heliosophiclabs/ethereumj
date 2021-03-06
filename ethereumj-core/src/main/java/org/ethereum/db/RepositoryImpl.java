package org.ethereum.db;

import org.ethereum.core.AccountState;
import org.ethereum.core.Block;
import org.ethereum.datasource.KeyValueDataSource;
import org.ethereum.facade.Repository;
import org.ethereum.json.EtherObjectMapper;
import org.ethereum.json.JSONHelper;
import org.ethereum.trie.Trie;
import org.ethereum.trie.TrieImpl;
import org.ethereum.vm.DataWord;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.spongycastle.util.encoders.Hex;

import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.math.BigInteger;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.ethereum.config.SystemProperties.CONFIG;
import static org.ethereum.crypto.SHA3Helper.sha3;
import static org.ethereum.util.ByteUtil.wrap;

/**
 * @author Roman Mandeleil
 * @since 17.11.2014
 */
public class RepositoryImpl implements Repository {

    public final static String DETAILS_DB = "details";
    public final static String STATE_DB = "state";

    private static final Logger logger = LoggerFactory.getLogger("repository");

    private Trie worldState;

    private DatabaseImpl detailsDB = null;
    private DatabaseImpl stateDB = null;

    KeyValueDataSource detailsDS = null;
    KeyValueDataSource stateDS = null;
    
    public RepositoryImpl() {
        this(DETAILS_DB, STATE_DB);
    }

    public RepositoryImpl(KeyValueDataSource detailsDS, KeyValueDataSource stateDS) {

        detailsDS.setName(DETAILS_DB);
        detailsDS.init();
        this.detailsDS = detailsDS;
        
        stateDS.setName(STATE_DB);
        stateDS.init();
        this.stateDS = stateDS;

        detailsDB = new DatabaseImpl(detailsDS);
        stateDB = new DatabaseImpl(stateDS);
        worldState = new TrieImpl(stateDB.getDb());
    }
    
    public RepositoryImpl(String detailsDbName, String stateDbName) {
        detailsDB = new DatabaseImpl(detailsDbName);
        stateDB = new DatabaseImpl(stateDbName);
        worldState = new TrieImpl(stateDB.getDb());
    }


    @Override
    public void reset() {
        close();

        detailsDS.init();
        detailsDB = new DatabaseImpl(detailsDS);
        
        stateDS.init();
        stateDB = new DatabaseImpl(stateDS);
        worldState = new TrieImpl(stateDB.getDb());
    }

    @Override
    public void close() {
        if (this.detailsDB != null) {
            detailsDB.close();
            detailsDB = null;
        }
        if (this.stateDB != null) {
            stateDB.close();
            stateDB = null;
        }
    }

    @Override
    public boolean isClosed() {
        return stateDB == null;
    }

    @Override
    public void updateBatch(HashMap<ByteArrayWrapper, AccountState> stateCache,
                            HashMap<ByteArrayWrapper, ContractDetails> detailsCache) {

        for (ByteArrayWrapper hash : stateCache.keySet()) {

            AccountState accountState = stateCache.get(hash);
            ContractDetails contractDetails = detailsCache.get(hash);

            if (accountState.isDeleted()) {
                worldState.delete(hash.getData());
                detailsDB.delete(hash.getData());

                logger.debug("delete: [{}]",
                        Hex.toHexString(hash.getData()));

            } else {

                if (accountState.isDirty() || contractDetails.isDirty()) {
                    detailsDB.put(hash.getData(), contractDetails.getEncoded());
                    accountState.setStateRoot(contractDetails.getStorageHash());
                    accountState.setCodeHash(sha3(contractDetails.getCode()));
                    worldState.update(hash.getData(), accountState.getEncoded());
                    if (logger.isDebugEnabled()) {
                        logger.debug("update: [{}],nonce: [{}] balance: [{}] \n [{}]",
                                Hex.toHexString(hash.getData()),
                                accountState.getNonce(),
                                accountState.getBalance(),
                                contractDetails.getStorage());
                    }

                }

            }

        }

        stateCache.clear();
        detailsCache.clear();
    }

    @Override
    public void flush() {
        logger.info("flush to disk");
        worldState.sync();
    }


    @Override
    public void rollback() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commit() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void syncToRoot(byte[] root) {
        worldState.setRoot(root);
    }

    @Override
    public Repository startTracking() {
        return new RepositoryTrack(this);
    }

    @Override
    public void dumpState(Block block, long gasUsed, int txNumber, byte[] txHash) {
        dumpTrie(block);

        if (!(CONFIG.dumpFull() || CONFIG.dumpBlock() == block.getNumber()))
            return;

        // todo: dump block header and the relevant tx

        if (block.getNumber() == 0 && txNumber == 0)
            if (CONFIG.dumpCleanOnRestart()) {
                FileSystemUtils.deleteRecursively(new File(CONFIG.dumpDir()));
            }

        String dir = CONFIG.dumpDir() + "/";

        String fileName = "";
        if (txHash != null)
            fileName = String.format("%07d_%d_%s.dmp", block.getNumber(), txNumber,
                    Hex.toHexString(txHash).substring(0, 8));
        else {
            fileName = String.format("%07d_c.dmp", block.getNumber());
        }

        File dumpFile = new File(System.getProperty("user.dir") + "/" + dir + fileName);
        FileWriter fw = null;
        BufferedWriter bw = null;
        try {

            dumpFile.getParentFile().mkdirs();
            dumpFile.createNewFile();

            fw = new FileWriter(dumpFile.getAbsoluteFile());
            bw = new BufferedWriter(fw);

            List<ByteArrayWrapper> keys = this.detailsDB.dumpKeys();

            JsonNodeFactory jsonFactory = new JsonNodeFactory(false);
            ObjectNode blockNode = jsonFactory.objectNode();

            JSONHelper.dumpBlock(blockNode, block, gasUsed,
                    this.getRoot(),
                    keys, this);

            EtherObjectMapper mapper = new EtherObjectMapper();
            bw.write(mapper.writeValueAsString(blockNode));

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

    public void dumpTrie(Block block) {

        if (!(CONFIG.dumpFull() || CONFIG.dumpBlock() == block.getNumber()))
            return;

        String fileName = String.format("%07d_trie.dmp", block.getNumber());
        String dir = CONFIG.dumpDir() + "/";
        File dumpFile = new File(System.getProperty("user.dir") + "/" + dir + fileName);
        FileWriter fw = null;
        BufferedWriter bw = null;

        String dump = this.worldState.getTrieDump();

        try {

            dumpFile.getParentFile().mkdirs();
            dumpFile.createNewFile();

            fw = new FileWriter(dumpFile.getAbsoluteFile());
            bw = new BufferedWriter(fw);

            bw.write(dump);

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


    @Override
    public Set<byte[]> getAccountsKeys() {
        return detailsDB.getDb().keys();
    }

    @Override
    public BigInteger addBalance(byte[] addr, BigInteger value) {

        AccountState account = getAccountState(addr);

        if (account == null)
            account = createAccount(addr);

        BigInteger result = account.addToBalance(value);
        worldState.update(addr, account.getEncoded());

        return result;
    }

    @Override
    public BigInteger getBalance(byte[] addr) {

        AccountState account = getAccountState(addr);

        if (account == null)
            return BigInteger.ZERO;

        return account.getBalance();
    }

    @Override
    public DataWord getStorageValue(byte[] addr, DataWord key) {

        ContractDetails details = getContractDetails(addr);

        if (details == null)
            return null;

        return details.get(key);
    }

    @Override
    public void addStorageRow(byte[] addr, DataWord key, DataWord value) {

        ContractDetails details = getContractDetails(addr);

        if (details == null) {
            createAccount(addr);
            details = getContractDetails(addr);
        }

        details.put(key, value);
        detailsDB.put(addr, details.getEncoded());
    }

    @Override
    public byte[] getCode(byte[] addr) {

        ContractDetails details = getContractDetails(addr);

        if (details == null)
            return null;

        return details.getCode();
    }

    @Override
    public void saveCode(byte[] addr, byte[] code) {
        ContractDetails details = getContractDetails(addr);

        if (details == null) {
            createAccount(addr);
            details = getContractDetails(addr);
        }

        details.setCode(code);
        detailsDB.put(addr, details.getEncoded());
    }


    @Override
    public BigInteger getNonce(byte[] addr) {

        AccountState account = getAccountState(addr);

        if (account == null)
            account = createAccount(addr);

        return account.getNonce();
    }

    @Override
    public BigInteger increaseNonce(byte[] addr) {

        AccountState account = getAccountState(addr);

        if (account == null)
            account = createAccount(addr);

        account.incrementNonce();
        worldState.update(addr, account.getEncoded());

        return account.getNonce();
    }

    public BigInteger setNonce(byte[] addr, BigInteger nonce) {

        AccountState account = getAccountState(addr);

        if (account == null)
            account = createAccount(addr);

        account.setNonce(nonce);
        worldState.update(addr, account.getEncoded());

        return account.getNonce();
    }


    @Override
    public void delete(byte[] addr) {
        worldState.delete(addr);
        detailsDB.delete(addr);
    }

    @Override
    public ContractDetails getContractDetails(byte[] addr) {

        ContractDetails result = null;
        byte[] detailsData = detailsDB.get(addr);

        if (detailsData != null)
            result = new ContractDetails(detailsData);

        return result;
    }

    @Override
    public AccountState getAccountState(byte[] addr) {

        AccountState result = null;
        byte[] accountData = worldState.get(addr);

        if (accountData.length != 0)
            result = new AccountState(accountData);

        return result;
    }

    @Override
    public AccountState createAccount(byte[] addr) {

        AccountState accountState = new AccountState();
        worldState.update(addr, accountState.getEncoded());

        ContractDetails contractDetails = new ContractDetails();
        detailsDB.put(addr, contractDetails.getEncoded());

        return accountState;
    }

    @Override
    public boolean isExist(byte[] addr) {
        return getAccountState(addr) != null;
    }

    @Override
    public void loadAccount(byte[] addr,
                            HashMap<ByteArrayWrapper, AccountState> cacheAccounts,
                            HashMap<ByteArrayWrapper, ContractDetails> cacheDetails) {

        AccountState account = getAccountState(addr);
        ContractDetails details = getContractDetails(addr);

        if (account == null)
            account = new AccountState();
        else
            account = account.clone();

        if (details == null)
            details = new ContractDetails();
        else
            details = details.clone();

        cacheAccounts.put(wrap(addr), account);
        cacheDetails.put(wrap(addr), details);
    }

    @Override
    public byte[] getRoot() {
        return worldState.getRootHash();
    }
}
