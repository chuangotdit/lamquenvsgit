package com.viettel.ewallet.core.business.plugin.pc002000;

import com.viettel.ewallet.coreapp.business.base.BaseBusiness;
import com.viettel.ewallet.coreapp.config.ConstantsManager;
import com.viettel.ewallet.database.entities.*;
import com.viettel.ewallet.database.query.CommonUtils;
import com.viettel.ewallet.database.query.DBUtils;
import com.viettel.ewallet.database.query.TheadInsertSMS;
import com.viettel.ewallet.database.utils.HibernateUtils;
import com.viettel.ewallet.pan.Pan;
import com.viettel.ewallet.utils.config.Constants;
import com.viettel.ewallet.utils.iso.msg.IsoObject;
import com.viettel.ewallet.utils.iso.msg.IsoRequest;
import com.viettel.msgcontent.utils.Config;
import org.apache.log4j.Logger;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;

import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class SelfRegisterBusiness
        extends BaseBusiness {

    private final String smsCust = "SMS_SELF_REGISTER";

    public SelfRegisterBusiness() {
        this.logger = Logger.getLogger(SelfRegisterBusiness.class.getSimpleName());
    }

    @Override
    protected IsoRequest onProcess(IsoRequest isoRequest) {
        this.logger.info("SelfRegisterBusiness|Begin");
        Transaction tx = null;
        Session session = HibernateUtils.openSession();
        IsoObject iso = isoRequest.getIsoObject();
        try {
            if (checkPending(session, iso.getCustomerPhone())) {
                iso.setResponseCode(Config.ErrorMap.CORE_PHONE_OR_IDNO_REGISTERED);
                iso.setResponseDescription("The PHONE NUMBER is waiting for approve create channel!");
                isoRequest.setIsoObject(iso);
                return isoRequest;
            }
            boolean ischeck = false;
            String local;
            if ((iso.getLanguage() != null) && (!iso.getLanguage().isEmpty())) {
                local = iso.getLanguage();
            } else {
                local = ConstantsManager.instance().getLanguageMap().get(
                        ConstantsManager.instance().getLanguageDefault()).toString();
            }
            tx = session.beginTransaction();
            Date currentDate = CommonUtils.getCurrentTime(session);
            CurrencyBO currencyBO;
            if ((iso.getCurrencyCode() == null) || (iso.getCurrencyCode().isEmpty())) {
                currencyBO = DBUtils.getInstance().getCurrencyByCode(session,
                        ConstantsManager.instance().getCurrentcyCodeDefault());
            } else {
                currencyBO = DBUtils.getInstance().getCurrencyByNumCode(session, iso.getCurrencyCode());
            }
            List<PartyRoleBO> lstPartyRoleByPhone = DBUtils.getInstance().getPartyRole(session, iso.getPhoneNumber());
            this.logger.info("lstPartyRoleByPhone ok");
            //

            if ((lstPartyRoleByPhone == null) || (lstPartyRoleByPhone.isEmpty())) {
                this.logger.info("Khach hang chua dang ky VDT");
                ischeck = true; // chua check subID
            }
            this.logger.info("ischeck " + ischeck);
            if (ischeck) {
                String strsubiD = iso.getTransactionDescription();
                Long subId = strsubiD != null ? Long.parseLong(strsubiD) : 0L;

                Long custId = 0L;
                String subType = "";
                String serviceType;
                serviceType = "0";

                //check partyPaper
                if (DBUtils.getInstance().getPartyPaperBO(session, iso.getPaperType(), iso.getPaperNumber(), 1L) != null) {
                    iso.setResponseCode(Config.ErrorMap.CORE_PAPER_INCORRECT);
                    iso.setResponseDescription("CORE_PAPER_INCORRECT");
                    isoRequest.setIsoObject(iso);
                    return isoRequest;
                }
                //end check partyPaper

                Long tier = iso.getTier() != null ? Long.parseLong(iso.getTier()) : 0L;

                if (iso.getCustomerAddress() == null || "".equals(iso.getCustomerAddress())) {
                    iso.setCustomerAddress("Incomplete information");
                }
                PartyBO partyBO = DBUtils.getInstance().createPartyBO(session, iso, currentDate, local);

                PartyPaperBO partyPaperBO = DBUtils.getInstance().createPartyPaper(session,
                        iso.getPaperType(), iso.getPaperNumber().toUpperCase(), null, partyBO.getPartyId(),
                        null, null, iso.getAreaCode(), currentDate);

                PartyRoleBO partyRoleBO = DBUtils.getInstance().createPartyRole(session,
                        partyBO.getPartyId(), iso.getPhoneNumber(), partyPaperBO.getPaperId(), custId, subId,
                        Constants.ROLE.CUSTOMER.longValue(), currentDate, subType, serviceType, tier);

                session.flush();
                Long accountID = DBUtils.getInstance().createAccount(Long.valueOf(Constants.ACCOUNT_STATE_ID.REGISTER),
                        Long.valueOf(Constants.BUSINESS.ACCOUNT_TYPE_DEFAULT.toString()),
                        partyRoleBO.getPartyRoleId(), iso.getPhoneNumber().substring(iso.getPhoneNumber().length() - 2),
                        currencyBO.getCurrencyCode(), currencyBO.getCurrencyId(), currencyBO.getCurrencyNumCode(), 1L, session);

                iso.setAccountID(accountID.toString());
                iso.setFromAccountId(accountID.toString());

                if (iso.getFileName() != null && !iso.getFileName().isEmpty()) {
                    logger.info(">>>=====================>SelfRegisterBusiness|saveFileAttach");
                }

                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "Create PartyBO, PartyPaper, PartyRole, AccountBO ",
                        Constants.LOG_TYPE.SAVE_DATA, Config.ErrorMap.SUCCESS, "Successfully", session);

                this.logger.info("accountID" + accountID);
                Pan pan = new Pan(session);
                String panStr = pan.createPan(accountID.intValue());
                iso.setPAN(panStr);

                this.logger.info("2");
                DBUtils.getInstance().insertRegisterActionBO(session,
                        partyRoleBO.getPartyId(), partyRoleBO.getPartyRoleId(), accountID,
                        iso.getPhoneNumber(), isoRequest.getActionId(), currentDate,
                        isoRequest.getTransactionId(), Constants.ROLE.CUSTOMER.longValue(),
                        iso.getProcessCode(), Long.parseLong(iso.getActionNode()), null, null, null, null);
                this.logger.info("3");
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "Create Pan, RegisterActionBO  ", Constants.LOG_TYPE.SAVE_DATA,
                        Config.ErrorMap.SUCCESS, "Successfully", session);

                new TheadInsertSMS(smsCust, new HashMap<>(), local, iso.getPhoneNumber());

                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "SendSms To Cust", Constants.LOG_TYPE.SENT_CUST,
                        Config.ErrorMap.SUCCESS, "Successfully", session);

                iso.setResponseCode(Config.ErrorMap.SUCCESS);
                iso.setResponseDescription("Register ewallet success");
                tx.commit();
            } else {
                this.logger.info("0 ");
                iso.setResponseCode(Config.ErrorMap.CORE_PHONE_NUMBER_REGISTERED_FINAL_ACC);
                iso.setResponseDescription("Your phone register ewallet already");
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "SelfRegisterBusiness: Fail  ", Constants.LOG_TYPE.CHECK_SUBS,
                        Config.ErrorMap.ERROR_UNKNOWN, iso.getResponseDescription(), session);
            }
            this.logger.info("end ");
            isoRequest.setIsoObject(iso);
        } catch (Exception ex) {
            this.logger.info("SelfRegisterBusiness - ERROR", ex);
            try {
                if (tx != null) {
                    tx.rollback();
                }
            } catch (Exception e) {
                this.logger.error("tx.rollback()", e);
            }
            try {
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "Exception", Constants.LOG_TYPE.EXCEPTION, Config.ErrorMap.ERROR_UNKNOWN,
                        ex.getMessage(), session);
                isoRequest.getIsoObject().setResponseCode(Config.ErrorMap.ERROR_UNKNOWN);
            } catch (Exception e) {
                this.logger.error("tx.rollback()", e);
            }
        } finally {
            this.logger.info("SelfRegisterBusiness|End");
            HibernateUtils.close(session);
        }
        return isoRequest;
    }

    public boolean checkPending(Session session, String msisdn) {
        Criteria cr = session.createCriteria(ChannelBO.class);
        cr.add(Restrictions.eq("phone", msisdn));
        cr.add(Restrictions.eq("status", 0L));
        List lst = cr.list();
        return lst != null && !lst.isEmpty();
    }
}
