package io.nuls.contract.token;

import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Contract;
import io.nuls.contract.sdk.Msg;
import io.nuls.contract.sdk.Utils;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

public class CrossChainToken extends Ownable implements Contract, Token {

    private final String name;
    private final String symbol;
    private final int decimals;
    private BigInteger totalSupply = BigInteger.ZERO;

    private Map<Address, BigInteger> balances = new HashMap<Address, BigInteger>();
    private Map<Address, Map<Address, BigInteger>> allowed = new HashMap<Address, Map<Address, BigInteger>>();
    /**
     * token跨链系统处理合约
     */
    private Address CROSS_TOKEN_SYSTEM_CONTRACT;

    @Override
    @View
    public String name() {
        return name;
    }

    @Override
    @View
    public String symbol() {
        return symbol;
    }

    @Override
    @View
    public int decimals() {
        return decimals;
    }

    @Override
    @View
    public BigInteger totalSupply() {
        return totalSupply;
    }

    public CrossChainToken(@Required String name, @Required String symbol, @Required BigInteger initialAmount, @Required int decimals) {
        this.name = name;
        this.symbol = symbol;
        this.decimals = decimals;
        totalSupply = initialAmount.multiply(BigInteger.TEN.pow(decimals));;
        balances.put(Msg.sender(), totalSupply);
        emit(new TransferEvent(null, Msg.sender(), totalSupply));
    }

    @Override
    @View
    public BigInteger allowance(@Required Address owner, @Required Address spender) {
        Map<Address, BigInteger> ownerAllowed = allowed.get(owner);
        if (ownerAllowed == null) {
            return BigInteger.ZERO;
        }
        BigInteger value = ownerAllowed.get(spender);
        if (value == null) {
            value = BigInteger.ZERO;
        }
        return value;
    }

    @Override
    public boolean transferFrom(@Required Address from, @Required Address to, @Required BigInteger value) {
        subtractAllowed(from, Msg.sender(), value);
        subtractBalance(from, value);
        addBalance(to, value);
        emit(new TransferEvent(from, to, value));
        return true;
    }

    @Override
    @View
    public BigInteger balanceOf(@Required Address owner) {
        require(owner != null);
        BigInteger balance = balances.get(owner);
        if (balance == null) {
            balance = BigInteger.ZERO;
        }
        return balance;
    }

    @Override
    public boolean transfer(@Required Address to, @Required BigInteger value) {
        subtractBalance(Msg.sender(), value);
        addBalance(to, value);
        emit(new TransferEvent(Msg.sender(), to, value));
        return true;
    }

    @Override
    public boolean approve(@Required Address spender, @Required BigInteger value) {
        setAllowed(Msg.sender(), spender, value);
        emit(new ApprovalEvent(Msg.sender(), spender, value));
        return true;
    }

    public boolean increaseApproval(@Required Address spender, @Required BigInteger addedValue) {
        addAllowed(Msg.sender(), spender, addedValue);
        emit(new ApprovalEvent(Msg.sender(), spender, allowance(Msg.sender(), spender)));
        return true;
    }

    public boolean decreaseApproval(@Required Address spender, @Required BigInteger subtractedValue) {
        check(subtractedValue);
        BigInteger oldValue = allowance(Msg.sender(), spender);
        if (subtractedValue.compareTo(oldValue) > 0) {
            setAllowed(Msg.sender(), spender, BigInteger.ZERO);
        } else {
            subtractAllowed(Msg.sender(), spender, subtractedValue);
        }
        emit(new ApprovalEvent(Msg.sender(), spender, allowance(Msg.sender(), spender)));
        return true;
    }

    /**
     * 加载token跨链系统处理合约
     */
    public void loadCrossTokenSystemContract() {
        CROSS_TOKEN_SYSTEM_CONTRACT = new Address((String) Utils.invokeExternalCmd("sc_getCrossTokenSystemContract", null));
    }

    /**
     * token跨链转账
     * @param to 平行链地址
     * @param value 转账金额
     * @return
     */
    @Payable
    public boolean transferCrossChain(@Required String to, @Required BigInteger value) {
        Address from = Msg.sender();
        // 授权系统合约可使用转出者的token资产(跨链部分)
        this.approve(crossTokenSystemContract(), value);

        // 调用系统合约，记录token资产跨链转出总额，生成合约资产跨链转账交易
        String methodName = "onNRC20Received";
        String[][] args = new String[][]{
                new String[]{from.toString()},
                new String[]{to},
                new String[]{value.toString()}};
        String returnValue = crossTokenSystemContract().callWithReturnValue(methodName, null, args, Msg.value());
        return Boolean.parseBoolean(returnValue);
    }

    private void addAllowed(Address address1, Address address2, BigInteger value) {
        BigInteger allowance = allowance(address1, address2);
        check(allowance);
        check(value);
        setAllowed(address1, address2, allowance.add(value));
    }

    private void subtractAllowed(Address address1, Address address2, BigInteger value) {
        BigInteger allowance = allowance(address1, address2);
        check(allowance, value, "Insufficient approved token");
        setAllowed(address1, address2, allowance.subtract(value));
    }

    private void setAllowed(Address address1, Address address2, BigInteger value) {
        check(value);
        Map<Address, BigInteger> address1Allowed = allowed.get(address1);
        if (address1Allowed == null) {
            address1Allowed = new HashMap<Address, BigInteger>();
            allowed.put(address1, address1Allowed);
        }
        address1Allowed.put(address2, value);
    }

    private void addBalance(Address address, BigInteger value) {
        BigInteger balance = balanceOf(address);
        check(value, "The value must be greater than or equal to 0.");
        check(balance);
        balances.put(address, balance.add(value));
    }

    private void subtractBalance(Address address, BigInteger value) {
        BigInteger balance = balanceOf(address);
        check(balance, value, "Insufficient balance of token.");
        balances.put(address, balance.subtract(value));
    }

    private void check(BigInteger value) {
        require(value != null && value.compareTo(BigInteger.ZERO) >= 0);
    }

    private void check(BigInteger value1, BigInteger value2) {
        check(value1);
        check(value2);
        require(value1.compareTo(value2) >= 0);
    }

    private void check(BigInteger value, String msg) {
        require(value != null && value.compareTo(BigInteger.ZERO) >= 0, msg);
    }

    private void check(BigInteger value1, BigInteger value2, String msg) {
        check(value1);
        check(value2);
        require(value1.compareTo(value2) >= 0, msg);
    }

    private Address crossTokenSystemContract() {
        if(CROSS_TOKEN_SYSTEM_CONTRACT == null) {
            loadCrossTokenSystemContract();
        }
        return CROSS_TOKEN_SYSTEM_CONTRACT;
    }

}
