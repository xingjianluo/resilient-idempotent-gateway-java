package com.github.xingjianluo.resilientidempotentgatewayjava.demo;

/**
 * 示例命令载荷：转账。含敏感字段（密码/令牌），日志必须脱敏。
 */
public class TransferRequest {

    private String fromAccount;
    private String toAccount;
    private long amountCents;
    private String credential;

    public String getFromAccount() { return fromAccount; }
    public void setFromAccount(String fromAccount) { this.fromAccount = fromAccount; }
    public String getToAccount() { return toAccount; }
    public void setToAccount(String toAccount) { this.toAccount = toAccount; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public String getCredential() { return credential; }
    public void setCredential(String credential) { this.credential = credential; }
}
