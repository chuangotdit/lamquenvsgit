/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.viettel.ewallet.core.business;

import com.viettel.ewallet.coreapp.business.base.BaseBusiness;
import com.viettel.ewallet.coreapp.config.ConstantsManager;
import com.viettel.ewallet.database.entities.ChannelBO;
import com.viettel.ewallet.database.entities.CurrencyBO;
import com.viettel.ewallet.database.entities.PartyBO;
import com.viettel.ewallet.database.entities.PartyPaperBO;
import com.viettel.ewallet.database.entities.PartyRoleBO;
import com.viettel.ewallet.database.entities.V_AccountStatusBO;
import com.viettel.ewallet.database.mapprocess.EWMapProcess;
import com.viettel.ewallet.database.mapprocess.EWMapProcessEx;
import com.viettel.ewallet.database.mapprocess.EWMapProcessServices;
import com.viettel.ewallet.database.mapprocess.EWProcessInfoBO;
import com.viettel.ewallet.database.pin.PinServices;
import com.viettel.ewallet.database.query.CommonUtils;
import com.viettel.ewallet.database.query.DBUtils;
import com.viettel.ewallet.database.query.TheadInsertSMS;
import com.viettel.ewallet.database.utils.HibernateUtils;
import com.viettel.ewallet.pan.Pan;
import com.viettel.ewallet.pin.PinException;
import com.viettel.ewallet.pin.PinExceptionCode;
import com.viettel.ewallet.utils.Utils;
import com.viettel.ewallet.utils.config.Constants;
import com.viettel.ewallet.utils.iso.msg.IsoObject;
import com.viettel.ewallet.utils.iso.msg.IsoRequest;
import com.viettel.msgcontent.utils.Config;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;
import org.jpos.iso.ISOException;

/**
 *
 * @author os_namta
 */
public class RegisterCustomerBusiness extends BaseBusiness {

    private static final String smsRegCustomer = "";

    @Override
    protected IsoRequest onProcess(IsoRequest isoRequest) {
        logger.info("Start 002001 bussiness");
        logger.info("RegisterCustomerBusiness|Begin");
        Transaction tx = null;
        Session session = HibernateUtils.openSession();
        IsoObject iso = isoRequest.getIsoObject();
        try {
            logger.info("Receive a request:" + getProcessCode() + " - " + getVarName());

            if (checkPending(session, iso.getCustomerPhone())) {
                iso.setResponseCode(Config.ErrorMap.CORE_PHONE_OR_IDNO_REGISTERED);
                iso.setResponseDescription("The PHONE NUMBER is waiting for approve create channel!");
                isoRequest.setIsoObject(iso);
                return isoRequest;
            }
            DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                    isoRequest.getActionId(), iso.getProcessCode(), 1L,
                    "CheckSubs",
                    Constants.LOG_TYPE.CHECK_SUBS, Config.ErrorMap.SUCCESS, "Successfully", session);

            Long accountId = Long.valueOf(iso.getAccountID());
            String phoneNumber = iso.getPhoneNumber();
            tx = session.beginTransaction();
            V_AccountStatusBO accountBO = DBUtils.getInstance().getAccountBO(session, accountId);
            if (accountBO == null || !accountBO.getMsisdn().equals(phoneNumber)) {
                iso.setResponseCode(Config.ErrorMap.CORE_ACCOUNT_NOT_REGISTER);
                iso.setResponseDescription("Your account was canceled or not registered. Please register again to use service");
                isoRequest.setIsoObject(iso);
                return isoRequest;
            }
            DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                    isoRequest.getActionId(), iso.getProcessCode(), 1L,
                    "CheckSubs",
                    Constants.LOG_TYPE.CHECK_SUBS, Config.ErrorMap.SUCCESS, "Successfully", session);

            /**
             * Check PIN
             */
            PinServices.checkPin(session, accountId, iso.getPIN(), iso.getProcessCode(), iso.getPhoneNumber(), isoRequest.getActionId());
            DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                    isoRequest.getActionId(), iso.getProcessCode(), 1L,
                    "CheckPin",
                    Constants.LOG_TYPE.CHECK_PIN, Config.ErrorMap.SUCCESS, "Successfully", session);

            boolean ischeck = false;
            String local;
            if ((iso.getLanguage() != null) && (!iso.getLanguage().isEmpty())) {
                local = iso.getLanguage();
            } else {
                local = ConstantsManager.instance().getLanguageMap().get(
                        ConstantsManager.instance().getLanguageDefault()).toString();
            }

            Date currentDate = CommonUtils.getCurrentTime(session);
            CurrencyBO currencyBO;
            if ((iso.getCurrencyCode() == null) || (iso.getCurrencyCode().isEmpty())) {
                currencyBO = DBUtils.getInstance().getCurrencyByCode(session,
                        ConstantsManager.instance().getCurrentcyCodeDefault());
            } else {
                currencyBO = DBUtils.getInstance().getCurrencyByNumCode(session, iso.getCurrencyCode());
            }

            this.logger.info("RegisterCustomerBusiness|GetPartyRoleByPhone " + iso.getCustomerPhone());
            List<PartyRoleBO> lstPartyRoleByPhone = DBUtils.getInstance().getPartyRole(session, iso.getCustomerPhone());

            if ((lstPartyRoleByPhone == null) || (lstPartyRoleByPhone.isEmpty())) {
                this.logger.info("RegisterCustomerBusiness|PartyRoleByPhone " + iso.getCustomerPhone() + " is empty");
                ischeck = true;
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "CheckSubs",
                        Constants.LOG_TYPE.CHECK_PIN, Config.ErrorMap.SUCCESS, "Successfully", session);
            } else {
                this.logger.info("RegisterCustomerBusiness|PartyRoleByPhone "
                        + iso.getCustomerPhone() + "=" + lstPartyRoleByPhone.size());
            }

            if (ischeck) {
                this.logger.info("Create party");
                PartyBO partyBO = DBUtils.getInstance().createPartyBO(session, iso, currentDate, local);

                this.logger.info("Create party paper");
                PartyPaperBO partyPaperBO = DBUtils.getInstance().createPartyPaper(session,
                        iso.getPaperType(), iso.getPaperNumber().toUpperCase(), null, partyBO.getPartyId(),
                        null, null, iso.getAreaCode(), currentDate);

                Long tier = iso.getTier() != null ? Long.parseLong(iso.getTier()) : 0L;

                this.logger.info("Create party role");
                PartyRoleBO partyRoleBO = DBUtils.getInstance().createPartyRole(session,
                        partyBO.getPartyId(), iso.getCustomerPhone(), partyPaperBO.getPaperId(), 0l, 0l,
                        Constants.ROLE.CUSTOMER.longValue(), currentDate, null, "0", tier);
                session.flush();
                this.logger.info("Create account");
                Long accId = DBUtils.getInstance().createAccount(Long.valueOf(Constants.ACCOUNT_STATE_ID.REGISTER),
                        Long.valueOf(Constants.BUSINESS.ACCOUNT_TYPE_DEFAULT.toString()),
                        partyRoleBO.getPartyRoleId(), iso.getCustomerPhone().substring(iso.getCustomerPhone().length() - 2),
                        currencyBO.getCurrencyCode(), currencyBO.getCurrencyId(), currencyBO.getCurrencyNumCode(), 1L, session);

                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "Create PartyBO, PartyPaper, PartyRole, AccountBO",
                        Constants.LOG_TYPE.SAVE_DATA, Config.ErrorMap.SUCCESS, "Successfully", session);
                session.flush();
                this.logger.info("Create PAN");
                Pan pan = new Pan(session);
                String panStr = pan.createPan(accId.intValue());
                iso.setPAN(panStr);

                this.logger.info("Insert register action");
                DBUtils.getInstance().insertRegisterActionBO(session,
                        partyRoleBO.getPartyId(), partyRoleBO.getPartyRoleId(), accId,
                        iso.getCustomerPhone(), isoRequest.getActionId(), currentDate,
                        isoRequest.getTransactionId(), Constants.ROLE.CUSTOMER.longValue(),
                        iso.getProcessCode(), Long.parseLong(iso.getActionNode()),
                        accountId.toString(), iso.getCarriedCode(), iso.getCarriedName(), null);

                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "RegisterActionBO", Constants.LOG_TYPE.SAVE_DATA,
                        Config.ErrorMap.SUCCESS, "Successfully", session);
                this.logger.info("Send sms to customer");

                sendSms(iso.getCustomerPhone(), smsRegCustomer, iso, accountBO.getPreferredLanguage(), iso.getCarriedName());
                iso.setResponseCode(Config.ErrorMap.SUCCESS);
            } else {
                this.logger.info("The PHONE NUMBER  registered ewallet already");
                iso.setResponseCode(Config.ErrorMap.CORE_PHONE_NUMBER_REGISTERED_FINAL_ACC);
                iso.setResponseDescription("The PHONE NUMBER  registered ewallet already");
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "RegisterCustomerBusiness: Fail  ", Constants.LOG_TYPE.CHECK_SUBS,
                        Config.ErrorMap.ERROR_UNKNOWN, iso.getResponseDescription(), session);
            }
            tx.commit();
            iso.setActionID(isoRequest.getActionId().toString());
            isoRequest.setIsoObject(iso);
        } catch (PinException ex) {
            logger.error("Check veryfy pin failed: " + ex.getErrorMsg(), ex);
            if (tx != null) {
                try {
                    tx.commit();
                } catch (Exception ex1) {
                    logger.error(ex1);
                }
            }
            try {
                if (PinExceptionCode.PIN_MAX_REQUEST.getId() == ex.getErrorCode()) {
                    isoRequest.getIsoObject().setResponseCode(Config.ErrorMap.CORE_PIN_BLOCKED);
                } else if (PinExceptionCode.PIN_INACTIVE.getId() == ex.getErrorCode()) {
                    isoRequest.getIsoObject().setResponseCode(Config.ErrorMap.CORE_ACCOUNT_INVALID);
                } else if (PinExceptionCode.PIN_WILL_BLOCK_NEXT_TIME.getId() == ex.getErrorCode()) {
                    isoRequest.getIsoObject().setResponseCode(Config.ErrorMap.CORE_WRONG_PIN_SECOND);
                } else {
                    isoRequest.getIsoObject().setResponseCode(Config.ErrorMap.CORE_WRONG_PIN);
                }

                isoRequest.getIsoObject().setResponseDescription(ex.getErrorMsg());
            } catch (ISOException e) {
                logger.error("", e);
            }
            try {
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L, "PIN Exception",
                        Constants.LOG_TYPE.EXCEPTION, isoRequest.getIsoObject().getResponseCode(),
                        ex.getErrorMsg(), session);
            } catch (Exception ex1) {
                this.logger.error("", ex1);
            }
        } catch (Exception ex) {
            logger.error("", ex);
            if (tx != null) {
                try {
                    tx.rollback();
                } catch (Exception e) {
                    logger.error("tx.rollback()", e);
                }
            }
            try {
                DBUtils.getInstance().insertTransLog(isoRequest.getTransactionId(),
                        isoRequest.getActionId(), iso.getProcessCode(), 1L,
                        "Exception",
                        Constants.LOG_TYPE.EXCEPTION, Config.ErrorMap.ERROR_UNKNOWN, ex.getMessage(), session);
                isoRequest.getIsoObject().setResponseCode(Config.ErrorMap.ERROR_UNKNOWN);
            } catch (Exception e) {
                logger.error("tx.rollback()", e);
            }
        } finally {
            logger.info("RegisterCustomerBusiness|End");
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

    private void sendSms(String phoneNumber, String key, IsoObject isoObject,
            String language, String agentName) {
        try {
            logger.info("RegisterCustomerBusiness|SendSMS to: " + phoneNumber);
            HashMap<String, String> hashContent = new HashMap<>();
            hashContent.put(Constants.PARA_MSG.TRANS_ID, isoObject.getTransactionId());
            hashContent.put(Constants.PARA_MSG.TO_PHONE_NUMBER, isoObject.getToPhone());
            hashContent.put(Constants.PARA_MSG.CUSTOMER_NAME, isoObject.getCustomerName());
            hashContent.put(Constants.PARA_MSG.PHONE_NUMBER, isoObject.getPhoneNumber());
            hashContent.put(Constants.PARA_MSG.AGENT_NAME, agentName);

            new TheadInsertSMS(key, hashContent, language, phoneNumber);
        } catch (ISOException e) {
            logger.info("Error in sendSMS: " + e);
        }
    }
}
