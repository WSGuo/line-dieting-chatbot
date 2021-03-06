package agent;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.google.common.io.ByteStreams;
import controller.ImageControl;
import controller.State;
import database.keeper.CampaignKeeper;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import utility.FormatterMessageJSON;
import utility.ParserMessageJSON;
import utility.TextProcessor;
import utility.Validator;

/**
 * CampaignManager: manage sharing, coupon claiming and coupon image upload.
 * 
 * State mapping:
 *      0 - branching (assign sharing code/check claimed code/start admin mode)
 *      1 - enable admin access
 *      2 - update available coupon (campaign already started)
 *      3 - set available coupon (campaign not started)
 *      4 - set coupon image
 *      5 - check coupon code
 * @author szhouan
 * @version v1.0.0
 */
@Slf4j
@Component
public class CampaignManager extends Agent {

    /**
     * This is a private attribute Usermanager.
     */
    @Autowired
    private UserManager userManager;

    /**
     * This is a integer storing the availble coupon num.
     */
    int availableCoupon = 0;

    /**
     * This is a string storing the admin code.
     */
    static final String ADMIN_ACCESS_CODE = "I am Sung Kim!!";

    /**
     * This is used to store the conpaign start instant.
     */
    Instant campaignStartInstant;

    /**
     * This is a string storing the coupon context.
     */
    String couponTextContent;

    /**
     * This is a int storing the num of current coupon.
     */
    int currentCouponId = 0;

    /**
     * Initialize campaign manager agent.
     */
    @Override
    public void init() {
        agentName = "CampaignManager";
        agentStates = new HashSet<>(
            Arrays.asList(State.INVITE_FRIEND, State.CLAIM_COUPON, State.MANAGE_CAMPAIGN)
        );
        handleImage = false;
        useSpellChecker = false;
        this.addHandler(0, (psr) -> branchHandler(psr))
            .addHandler(1, (psr) -> enableAdminAccess(psr))
            .addHandler(2, (psr) -> updateCouponNumber(psr))
            .addHandler(3, (psr) -> startCampaign(psr))
            .addHandler(4, (psr) -> setCouponImage(psr))
            .addHandler(5, (psr) -> checkCoupon(psr));
    }

    /**
     * Handler for first interaction, providing branching.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int branchHandler(ParserMessageJSON psr) {
        State state = psr.getState();
        if (state == State.INVITE_FRIEND) {
            return inviteFriendHandler(psr);
        } else if (state == State.CLAIM_COUPON) {
            return claimCouponHandler(psr);
        } else {
            return campaignManageHandler(psr);
        }
    }

    /**
     * Handler for inviting friend.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int inviteFriendHandler(ParserMessageJSON psr) {
        String userId = psr.getUserId();

        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (availableCoupon <= 0) {
            fmt.appendTextMessage("Sorry, the campaign is not ongoing at this stage. " +
                "Please keep an eye on this so that you won't miss it! ^_^");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        CampaignKeeper keeper = getCampaignKeeper();
        int couponCount = Integer.parseInt(keeper.getCouponCnt());
        if (couponCount >= availableCoupon) {
            fmt.appendTextMessage("I am sorry that we do not have available coupons now. " +
                "Please come earlier next time! ^_^");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            keeper.close();
            return END_STATE;
        }

        fmt.appendTextMessage("Thank you for promoting our service! ^_^")
           .appendTextMessage(String.format("This is your sharing code: %06d", currentCouponId));
        keeper.setParentUserId(String.format("%06d", currentCouponId), userId);
        keeper.close();
        ++currentCouponId;

        publisher.publish(fmt);
        controller.setUserState(userId, State.IDLE);
        return END_STATE;
    }

    /**
     * Handler for claiming coupon.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int claimCouponHandler(ParserMessageJSON psr) {
        String userId = psr.getUserId();
        
        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        fmt.appendTextMessage("Great! What is your sharing code for the coupon? " +
            "Please input the 6 digit code only.");
        publisher.publish(fmt);
        return 5;
    }

    /**
     * Handler for checking coupon id.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int checkCoupon(ParserMessageJSON psr) {
        String userId = psr.getUserId();
        String text = psr.get("textContent").trim();

        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (text.length() != 6 || !Validator.isInteger(text)) {
            fmt.appendTextMessage("Sorry, the format for the coupon claimed is invalid")
               .appendTextMessage("The correct format should be 'code <6-digit number>'.");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        if (availableCoupon <= 0) {
            fmt.appendTextMessage("Sorry, the campaign is not ongoing at this stage. " +
                "Please stay tuned on this :)");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        CampaignKeeper keeper = getCampaignKeeper();
        // log.info("COUPON COUNT = " + keeper.getCouponCnt());
        int couponCount = Integer.parseInt(keeper.getCouponCnt());
        if (couponCount >= availableCoupon) {
            fmt.appendTextMessage("Sorry, but all the available coupons are now distributed. " +
                "Please be earlier next time ~");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            keeper.close();
            return END_STATE;
        }

        String parentUserId = keeper.getParentUserId(text);
        keeper.close();
        if (parentUserId == null) {
            fmt.appendTextMessage("Well, this is not a valid sharing Id.")
               .appendTextMessage("Please double check. Session cancelled.");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        JSONObject userJSON = userManager.getUserJSON(userId);
        if (userJSON == null) {
            fmt.appendTextMessage("Well, I don't have your personal information yet, " +
                "so you cannot claim the coupon now.")
               .appendTextMessage("Please do so by 'setting'. Session cancelled.");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        if (userJSON.keySet().contains("parentUserId")) {
            fmt.appendTextMessage("Well, one user can only claim the coupon using 'code' once.")
               .appendTextMessage("But you can share this chatbot with your friends to " +
                "get more coupons!");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        if (parentUserId.equals(userId)) {
            fmt.appendTextMessage("Well, seems that this code is issued as your sharing code, " +
                "so you cannot self-claim it :(");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        // check whether is new user
        String followTime = userJSON.getString("followTime");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long follow = 0;
        try {
            Date timestamp = format.parse(followTime);
            follow = timestamp.getTime();
        } catch (Exception e) {
            log.info(e.toString());
        }
        long start = campaignStartInstant.getEpochSecond() * 1000;
        log.info("follow string: {}", followTime);
        log.info(String.format("Follow: %d Start: %d", follow, start));
        if (follow < start) {
            fmt.appendTextMessage("Sorry, you are not a user following us after the campaign start.");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        // update database
        userJSON.put("parentUserId", parentUserId);
        userManager.storeUserJSON(userId, userJSON);
        keeper = getCampaignKeeper();
        keeper.incrCouponCnt();
        keeper.close();

        keeper = getCampaignKeeper();
        String encodedString = keeper.getCouponImg();
        keeper.close();

        boolean hasImage = encodedString != null;
        String uri = null;
        if (hasImage)
            uri = ImageControl.getCouponImageUri(userId, encodedString, "png");

        fmt.appendTextMessage("Congratulations! This is the coupon you claimed:");
        if (hasImage) {
            fmt.appendImageMessage(uri, uri);
        } else {
            fmt.appendTextMessage("NO IMAGE FOR COUPON YET");
        }
        publisher.publish(fmt);
        sleep();

        fmt = new FormatterMessageJSON(parentUserId);
        fmt.appendTextMessage("Hey, one more user follows our chatbot by your promotion ~")
           .appendTextMessage("This coupon is rewarded to you:");
        if (hasImage) {
            fmt.appendImageMessage(uri, uri);
        } else {
            fmt.appendTextMessage("NO IMAGE FOR COUPON YET");
        }
        publisher.publish(fmt);
        sleep();

        controller.setUserState(userId, State.IDLE);
        return END_STATE;
    }

    /**
     * Handler for campaign management.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int campaignManageHandler(ParserMessageJSON psr) {
        String userId = psr.getUserId();

        JSONObject userJSON = userManager.getUserJSON(userId);
        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (userJSON == null) {
            fmt.appendTextMessage("Sorry, we cannot verify your admin identity. " +
                "Please do 'setting' first. Session cancelled.");
            publisher.publish(fmt);

            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        boolean isAdmin = true;
        if (!userJSON.keySet().contains("isAdmin")) isAdmin = false;
        else if (!userJSON.getBoolean("isAdmin")) isAdmin = false;
        if (!isAdmin) {
            fmt.appendTextMessage("You are not an admin user now. " +
                "If you want to enable your admin priviledge, please input your admin access code.");
            publisher.publish(fmt);
            return 1;
        }

        fmt.appendTextMessage("Hi admin user! ^_^");
        if (availableCoupon > 0) {
            fmt.appendTextMessage("The campaign is now open, and the number of " +
                "available coupon is " + availableCoupon + ".")
               .appendTextMessage("Do you want to update it? Tell me a number or say 'skip'.");
            publisher.publish(fmt);
            return 2;
        } else {
            fmt.appendTextMessage("The campaign is not open now. Do you want to start it now?")
               .appendTextMessage("Input a positive number as the number of available coupons " +
                "for this campaign, or say 'no'.");
            publisher.publish(fmt);
            return 3;
        }
    }

    /**
     * Handler for enabling admin access.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int enableAdminAccess(ParserMessageJSON psr) {
        String userId = psr.getUserId();
        String text = psr.get("textContent");

        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (text.equals(ADMIN_ACCESS_CODE)) {
            fmt.appendTextMessage("Admin mode has been enabled for you.");
            publisher.publish(fmt);
            sleep();

            log.info("{}: update UserJSON", agentName);
            JSONObject userJSON = userManager.getUserJSON(userId);
            userJSON.put("isAdmin", true);
            userManager.storeUserJSON(userId, userJSON);

            return campaignManageHandler(psr);
        } else {
            fmt.appendTextMessage("Incorrect access code. Access denied.");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }
    }

    /**
     * Handler for updating number of available coupons.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int updateCouponNumber(ParserMessageJSON psr) {
        String userId = psr.getUserId();
        String text = psr.get("textContent").toLowerCase();

        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (Validator.isInteger(text)) {
            int couponNumber = Integer.parseInt(text);
            if (couponNumber <= 0) {
                availableCoupon = 0;
                fmt.appendTextMessage("Set available coupon to non-positive, campaign stopped.");
                publisher.publish(fmt);

                controller.setUserState(userId, State.IDLE);
                return END_STATE;
            } else {
                availableCoupon = couponNumber;
                fmt.appendTextMessage("Set available coupon to " + availableCoupon + ".");
            }
        } else {
            if (TextProcessor.getMatch(TextProcessor.getTokens(text),
                Arrays.asList("no", "nope", "skip", "next")) != null) {
                fmt.appendTextMessage("Update coupon number skipped.");
            } else {
                rejectUserInput(psr, "Please tell me the updated available coupon number, " +
                    "or ask me to skip this part explicitly.");
                return 2;
            }
        }

        states.get(userId).put("usePrefix", true);
        fmt.appendTextMessage("Now do you want to update the image of the coupon? " +
            "If yes, send an image to me. Otherwise, please say 'CANCEL'.");
        publisher.publish(fmt);
        handleImage = true;
        log.info("{}: enable image handling", agentName);
        return 4;
    }

    /**
     * Handler for starting campaign.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int startCampaign(ParserMessageJSON psr) {
        String userId = psr.getUserId();
        String text = psr.get("textContent");

        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (Validator.isInteger(text)) {
            int num = Integer.parseInt(text);
            if (num > 0) {
                availableCoupon = num;
                states.get(userId).put("usePrefix", false);
                campaignStartInstant = Instant.now();
                fmt.appendTextMessage("Start campaign with a total of " + num + " coupon(s).");
                fmt.appendTextMessage("Now please set the image of the coupon. You can say 'CANCEL' to skip.");
                publisher.publish(fmt);
                handleImage = true;
                log.info("{}: enable image handling", agentName);

                CampaignKeeper keeper = getCampaignKeeper();
                keeper.resetCouponCnt();
                keeper.close();
                return 4;
            } else {
                rejectUserInput(psr, "Either input a positive number, or say you do not want " +
                    "to start a campaign explicitly.");
                return 3;
            }
        } else {
            if (TextProcessor.getMatch(TextProcessor.getTokens(text),
                Arrays.asList("no", "nope", "n't")) != null) {
                fmt.appendTextMessage("OK, cancelling to start a campaign.");
                publisher.publish(fmt);
                controller.setUserState(userId, State.IDLE);
                return END_STATE;
            } else {
                rejectUserInput(psr, "I don't understand what you have said.");
                return 3;
            }
        }
    }

    /**
     * Handler for setting coupon image.
     * @param psr Input ParserMessageJSON
     * @return next state
     */
    public int setCouponImage(ParserMessageJSON psr) {
        String userId = psr.getUserId();
        String type = psr.getType();

        FormatterMessageJSON fmt = new FormatterMessageJSON(userId);
        if (type.equals("text")) {
            rejectUserInput(psr, "Please send me a photo or say 'CANCEL'.");
            return 4;
        }

        String uriString = psr.get("imageContent");
        log.info("URI {}", uriString);
        String encodedString = null;
        try {
            InputStream is = (new URI(uriString)).toURL().openStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            long numOfBytes = ByteStreams.copy(is, bos);
            log.info("copied " + numOfBytes + " bytes");
            byte[] buf = bos.toByteArray();
            encodedString = Base64.encodeBase64URLSafeString(buf);
            // log.info("Encoded String in Base64: {}", encodedString);
        } catch (Exception e) {
            log.info(e.toString());
        }

        if (encodedString == null) {
            fmt.appendTextMessage("ERROR in getting the image, session cancelled");
            publisher.publish(fmt);
            controller.setUserState(userId, State.IDLE);
            return END_STATE;
        }

        CampaignKeeper keeper = getCampaignKeeper();
        keeper.setCouponImg(encodedString);
        keeper.close();

        fmt.appendTextMessage("Set image of the coupon succeeded.")
           .appendTextMessage("Leaving admin mode for campaign management.");
        publisher.publish(fmt);
        controller.setUserState(userId, State.IDLE);
        handleImage = false;
        log.info("{}: disable image handling", agentName);
        return END_STATE;
    }

    /**
     * Get campaign keeper.
     * @return A campaign keeper
     */
    CampaignKeeper getCampaignKeeper() {
        return new CampaignKeeper();
    }
}