/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.subscription.services.impl;

import io.harness.ModuleType;
import io.harness.beans.FeatureName;
import io.harness.exception.InvalidArgumentsException;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.UnsupportedOperationException;
import io.harness.licensing.Edition;
import io.harness.licensing.LicenseType;
import io.harness.licensing.entities.modules.CFModuleLicense;
import io.harness.licensing.entities.modules.ModuleLicense;
import io.harness.licensing.helpers.ModuleLicenseHelper;
import io.harness.repositories.ModuleLicenseRepository;
import io.harness.repositories.StripeCustomerRepository;
import io.harness.repositories.SubscriptionDetailRepository;
import io.harness.subscription.constant.Prices;
import io.harness.subscription.dto.CustomerDTO;
import io.harness.subscription.dto.CustomerDetailDTO;
import io.harness.subscription.dto.FfSubscriptionDTO;
import io.harness.subscription.dto.InvoiceDetailDTO;
import io.harness.subscription.dto.PaymentMethodCollectionDTO;
import io.harness.subscription.dto.PriceCollectionDTO;
import io.harness.subscription.dto.StripeBillingDTO;
import io.harness.subscription.dto.SubscriptionDTO;
import io.harness.subscription.dto.SubscriptionDetailDTO;
import io.harness.subscription.entities.StripeCustomer;
import io.harness.subscription.entities.SubscriptionDetail;
import io.harness.subscription.handlers.StripeEventHandler;
import io.harness.subscription.helpers.StripeHelper;
import io.harness.subscription.params.BillingParams;
import io.harness.subscription.params.CustomerParams;
import io.harness.subscription.params.CustomerParams.CustomerParamsBuilder;
import io.harness.subscription.params.ItemParams;
import io.harness.subscription.params.SubscriptionParams;
import io.harness.subscription.params.UsageKey;
import io.harness.subscription.services.SubscriptionService;
import io.harness.subscription.utils.NGFeatureFlagHelperService;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.val;
import org.apache.commons.validator.routines.EmailValidator;

@Singleton
public class SubscriptionServiceImpl implements SubscriptionService {
  private final StripeHelper stripeHelper;
  private final ModuleLicenseRepository licenseRepository;
  private final StripeCustomerRepository stripeCustomerRepository;
  private final SubscriptionDetailRepository subscriptionDetailRepository;
  private final NGFeatureFlagHelperService nGFeatureFlagHelperService;

  private final Map<String, StripeEventHandler> eventHandlers;

  private static final double RECOMMENDATION_MULTIPLIER = 1.2d;

  @Inject
  public SubscriptionServiceImpl(StripeHelper stripeHelper, ModuleLicenseRepository licenseRepository,
      StripeCustomerRepository stripeCustomerRepository, SubscriptionDetailRepository subscriptionDetailRepository,
      NGFeatureFlagHelperService nGFeatureFlagHelperService, Map<String, StripeEventHandler> eventHandlers) {
    this.stripeHelper = stripeHelper;
    this.licenseRepository = licenseRepository;
    this.stripeCustomerRepository = stripeCustomerRepository;
    this.subscriptionDetailRepository = subscriptionDetailRepository;
    this.nGFeatureFlagHelperService = nGFeatureFlagHelperService;
    this.eventHandlers = eventHandlers;
  }

  @Override
  public EnumMap<UsageKey, Long> getRecommendation(String accountIdentifier, long numberOfMAUs, long numberOfUsers) {
    List<ModuleLicense> currentLicenses =
        licenseRepository.findByAccountIdentifierAndModuleType(accountIdentifier, ModuleType.CF);

    if (currentLicenses.isEmpty()) {
      throw new InvalidRequestException(
          String.format("Cannot provide recommendation. No active license detected for module %s.", ModuleType.CF));
    }

    ModuleLicense latestLicense = ModuleLicenseHelper.getLatestLicense(currentLicenses);

    CFModuleLicense cfLicense = (CFModuleLicense) latestLicense;

    EnumMap<UsageKey, Long> recommendedValues = new EnumMap<>(UsageKey.class);

    LicenseType licenseType = latestLicense.getLicenseType();
    Edition edition = latestLicense.getEdition();
    if (licenseType.equals(LicenseType.TRIAL)) {
      double recommendedUsers = Math.max(cfLicense.getNumberOfUsers(), numberOfUsers) * RECOMMENDATION_MULTIPLIER;
      double recommendedMAUs = Math.max(cfLicense.getNumberOfClientMAUs(), numberOfMAUs) * RECOMMENDATION_MULTIPLIER;

      recommendedValues.put(UsageKey.NUMBER_OF_USERS, (long) recommendedUsers);
      recommendedValues.put(UsageKey.NUMBER_OF_MAUS, (long) recommendedMAUs);
    } else if (licenseType.equals(LicenseType.PAID) || edition.equals(Edition.FREE)) {
      double recommendedUsers = Math.max(cfLicense.getNumberOfUsers(), numberOfUsers);
      double recommendedMAUs = Math.max(cfLicense.getNumberOfClientMAUs(), numberOfMAUs);

      recommendedValues.put(UsageKey.NUMBER_OF_USERS, (long) recommendedUsers);
      recommendedValues.put(UsageKey.NUMBER_OF_MAUS, (long) recommendedMAUs);
    } else {
      throw new InvalidRequestException(
          String.format("Cannot provide recommendation. No active license detected for module %s.", ModuleType.CF));
    }

    return recommendedValues;
  }

  @Override
  public PriceCollectionDTO listPrices(String accountIdentifier, ModuleType module) {
    isSelfServiceEnable(accountIdentifier);

    return stripeHelper.getPrices(module);
  }

  @Override
  public InvoiceDetailDTO previewInvoice(String accountIdentifier, SubscriptionDTO subscriptionDTO) {
    isSelfServiceEnable(accountIdentifier);

    StripeCustomer stripeCustomer = stripeCustomerRepository.findByAccountIdentifierAndCustomerId(
        accountIdentifier, subscriptionDTO.getCustomerId());
    if (stripeCustomer == null) {
      throw new InvalidRequestException("Cannot preview. Please finish customer information firstly");
    }

    SubscriptionParams params = SubscriptionParams.builder().build();
    params.setItems(subscriptionDTO.getItems());
    params.setCustomerId(stripeCustomer.getCustomerId());

    SubscriptionDetail subscriptionDetail = subscriptionDetailRepository.findByAccountIdentifierAndModuleType(
        accountIdentifier, subscriptionDTO.getModuleType());
    // Only preview proration when there is an active subscription
    if (subscriptionDetail != null && !subscriptionDetail.isIncomplete()) {
      params.setSubscriptionId(subscriptionDetail.getSubscriptionId());
    }

    return stripeHelper.previewInvoice(params);
  }

  @Override
  public void payInvoice(String invoiceId) {
    stripeHelper.payInvoice(invoiceId);
  }

  @Override
  public SubscriptionDetailDTO createFfSubscription(String accountIdentifier, FfSubscriptionDTO subscriptionDTO) {
    isSelfServiceEnable(accountIdentifier);

    // TODO: transaction control in case any race condition

    // verify customer exists
    StripeCustomer stripeCustomer = stripeCustomerRepository.findByAccountIdentifier(accountIdentifier);
    if (stripeCustomer == null) {
      createStripeCustomer(accountIdentifier, subscriptionDTO.getCustomer());
      stripeCustomer = stripeCustomerRepository.findByAccountIdentifier(accountIdentifier);
    } else {
      updateStripeCustomer(accountIdentifier, stripeCustomer.getCustomerId(), subscriptionDTO.getCustomer());
    }

    // Not allowed for creation if active subscriptionId exists
    SubscriptionDetail subscriptionDetail =
        subscriptionDetailRepository.findByAccountIdentifierAndModuleType(accountIdentifier, ModuleType.valueOf("CF"));
    if (subscriptionDetail != null) {
      if (!subscriptionDetail.isIncomplete()) {
        throw new InvalidRequestException("Cannot create a new subscription, since there is an active one.");
      }

      // cancel incomplete subscription
      cancelSubscription(subscriptionDetail.getAccountIdentifier(), subscriptionDetail.getSubscriptionId());
    }

    ArrayList<ItemParams> subscriptionItems = new ArrayList<>();

    val developerPriceId = stripeHelper.getPrice(
        ModuleType.CF, "DEVELOPERS", subscriptionDTO.getEdition(), subscriptionDTO.getPaymentFreq());

    subscriptionItems.add(ItemParams.builder()
                              .priceId(developerPriceId.getId())
                              .quantity((long) subscriptionDTO.getNumberOfDevelopers())
                              .build());

    val mauPriceId = stripeHelper.getPrice(ModuleType.CF, "MAU", subscriptionDTO.getEdition(),
        subscriptionDTO.getPaymentFreq(), subscriptionDTO.getNumberOfMau());

    subscriptionItems.add(ItemParams.builder().priceId(mauPriceId.getId()).quantity(1L).build());

    if (subscriptionDTO.isPremiumSupport()) {
      if (subscriptionDTO.isMonthly()) {
        throw new InvalidArgumentsException("Cannot subscribe to premium support with a monthly renewal rate.");
      }
      val mauSupportPriceId = stripeHelper.getPrice(ModuleType.CF, "MAU_SUPPORT", subscriptionDTO.getEdition(),
          subscriptionDTO.getPaymentFreq(), subscriptionDTO.getNumberOfMau());

      subscriptionItems.add(new ItemParams(mauSupportPriceId.getId(), 1L, Prices.PREMIUM_SUPPORT));

      val developerSupportPriceId = stripeHelper.getPrice(
          ModuleType.CF, "DEVELOPERS_SUPPORT", subscriptionDTO.getEdition(), subscriptionDTO.getPaymentFreq());

      subscriptionItems.add(new ItemParams(
          developerSupportPriceId.getId(), (long) subscriptionDTO.getNumberOfDevelopers(), Prices.PREMIUM_SUPPORT));
    }

    // create Subscription
    SubscriptionParams param = SubscriptionParams.builder()
                                   .accountIdentifier(accountIdentifier)
                                   .moduleType("CF")
                                   .customerId(stripeCustomer.getCustomerId())
                                   .items(subscriptionItems)
                                   .paymentFrequency(subscriptionDTO.getPaymentFreq())
                                   .customerEmail(subscriptionDTO.getCustomer().getBillingEmail())
                                   .build();

    SubscriptionDetailDTO subscription = stripeHelper.createSubscription(param);

    // Save locally with basic information after succeed
    subscriptionDetailRepository.save(SubscriptionDetail.builder()
                                          .accountIdentifier(accountIdentifier)
                                          .customerId(stripeCustomer.getCustomerId())
                                          .subscriptionId(subscription.getSubscriptionId())
                                          .status("incomplete")
                                          .latestInvoice(subscription.getLatestInvoice())
                                          .moduleType(ModuleType.CF)
                                          .build());

    return subscription;
  }

  @Override
  public SubscriptionDetailDTO createSubscription(String accountIdentifier, SubscriptionDTO subscriptionDTO) {
    isSelfServiceEnable(accountIdentifier);

    // TODO: transaction control in case any race condition

    // verify customer exists
    StripeCustomer stripeCustomer = stripeCustomerRepository.findByAccountIdentifierAndCustomerId(
        accountIdentifier, subscriptionDTO.getCustomerId());
    if (stripeCustomer == null) {
      throw new InvalidRequestException("Cannot create subscription. Please finish customer information firstly");
    }

    // Not allowed for creation if active subscriptionId exists
    SubscriptionDetail subscriptionDetail = subscriptionDetailRepository.findByAccountIdentifierAndModuleType(
        accountIdentifier, subscriptionDTO.getModuleType());
    if (subscriptionDetail != null) {
      if (!subscriptionDetail.isIncomplete()) {
        throw new InvalidRequestException("Cannot create a new subscription, since there is an active one.");
      }

      // cancel incomplete subscription
      cancelSubscription(subscriptionDetail.getAccountIdentifier(), subscriptionDetail.getSubscriptionId());
    }

    // create Subscription
    SubscriptionParams param = SubscriptionParams.builder()
                                   .accountIdentifier(accountIdentifier)
                                   .moduleType(subscriptionDTO.getModuleType().name())
                                   .customerId(stripeCustomer.getCustomerId())
                                   .paymentMethodId(subscriptionDTO.getPaymentMethodId())
                                   .items(subscriptionDTO.getItems())
                                   .build();
    SubscriptionDetailDTO subscription = stripeHelper.createSubscription(param);

    // Save locally with basic information after succeed
    subscriptionDetailRepository.save(SubscriptionDetail.builder()
                                          .accountIdentifier(accountIdentifier)
                                          .customerId(stripeCustomer.getCustomerId())
                                          .subscriptionId(subscription.getSubscriptionId())
                                          .status(subscription.getStatus())
                                          .latestInvoice(subscription.getLatestInvoice())
                                          .moduleType(subscriptionDTO.getModuleType())
                                          .build());
    return subscription;
  }

  @Override
  public SubscriptionDetailDTO updateSubscription(
      String accountIdentifier, String subscriptionId, SubscriptionDTO subscriptionDTO) {
    isSelfServiceEnable(accountIdentifier);

    SubscriptionDetail subscriptionDetail = subscriptionDetailRepository.findBySubscriptionId(subscriptionId);
    if (checkSubscriptionInValid(subscriptionDetail, accountIdentifier)) {
      throw new InvalidRequestException("Invalid subscriptionId");
    }
    // TODO: verify priceId is acceptable to update, could utilize local price cache

    SubscriptionParams param = SubscriptionParams.builder()
                                   .accountIdentifier(accountIdentifier)
                                   .subscriptionId(subscriptionDetail.getSubscriptionId())
                                   .paymentMethodId(subscriptionDTO.getPaymentMethodId())
                                   .items(subscriptionDTO.getItems())
                                   .build();
    return stripeHelper.updateSubscription(param);
  }
  @Override
  public void cancelSubscription(String accountIdentifier, String subscriptionId) {
    isSelfServiceEnable(accountIdentifier);

    SubscriptionDetail subscriptionDetail = subscriptionDetailRepository.findBySubscriptionId(subscriptionId);
    if (checkSubscriptionInValid(subscriptionDetail, accountIdentifier)) {
      throw new InvalidRequestException("Invalid subscriptionId");
    }

    stripeHelper.cancelSubscription(
        SubscriptionParams.builder().subscriptionId(subscriptionDetail.getSubscriptionId()).build());
    subscriptionDetailRepository.deleteBySubscriptionId(subscriptionId);
  }

  @Override
  public void cancelAllSubscriptions(String accountIdentifier) {
    isSelfServiceEnable(accountIdentifier);

    List<SubscriptionDetail> subscriptionDetails =
        subscriptionDetailRepository.findByAccountIdentifier(accountIdentifier);
    subscriptionDetails.forEach(subscriptionDetail -> {
      if (subscriptionDetail.isActive()) {
        cancelSubscription(accountIdentifier, subscriptionDetail.getSubscriptionId());
      }
    });
  }
  @Override
  public SubscriptionDetailDTO getSubscription(String accountIdentifier, String subscriptionId) {
    isSelfServiceEnable(accountIdentifier);

    SubscriptionDetail subscriptionDetail = subscriptionDetailRepository.findBySubscriptionId(subscriptionId);
    if (checkSubscriptionInValid(subscriptionDetail, accountIdentifier)) {
      throw new InvalidRequestException("Invalid subscriptionId");
    }

    return stripeHelper.retrieveSubscription(SubscriptionParams.builder().subscriptionId(subscriptionId).build());
  }

  @Override
  public boolean checkSubscriptionExists(String subscriptionId) {
    SubscriptionDetail subscriptionDetail = subscriptionDetailRepository.findBySubscriptionId(subscriptionId);
    return subscriptionDetail != null;
  }

  @Override
  public List<SubscriptionDetailDTO> listSubscriptions(String accountIdentifier, ModuleType moduleType) {
    isSelfServiceEnable(accountIdentifier);

    List<SubscriptionDetail> subscriptions = new ArrayList<>();
    if (moduleType == null) {
      subscriptions = subscriptionDetailRepository.findByAccountIdentifier(accountIdentifier);
    } else {
      SubscriptionDetail subscriptionDetail =
          subscriptionDetailRepository.findByAccountIdentifierAndModuleType(accountIdentifier, moduleType);
      if (subscriptionDetail != null) {
        subscriptions.add(subscriptionDetail);
      }
    }

    return subscriptions.stream()
        .map(detail
            -> stripeHelper.retrieveSubscription(
                SubscriptionParams.builder().subscriptionId(detail.getSubscriptionId()).build()))
        .collect(Collectors.toList());
  }

  @Override
  public CustomerDetailDTO createStripeCustomer(String accountIdentifier, CustomerDTO customerDTO) {
    isSelfServiceEnable(accountIdentifier);

    if (!EmailValidator.getInstance().isValid(customerDTO.getBillingEmail())) {
      throw new InvalidRequestException("Billing email is invalid");
    }
    if (Strings.isNullOrEmpty(customerDTO.getCompanyName())) {
      throw new InvalidRequestException("Company name is invalid");
    }
    // TODO: double check if accountIdnetifer already bind to one customer

    CustomerDetailDTO customerDetailDTO =
        stripeHelper.createCustomer(CustomerParams.builder()
                                        .accountIdentifier(accountIdentifier)
                                        .address(customerDTO.getAddress())
                                        .billingContactEmail(customerDTO.getBillingEmail())
                                        .name(customerDTO.getCompanyName())
                                        .build());

    // Save customer information at local After succeed
    saveCustomerLocally(accountIdentifier, null, customerDetailDTO);
    return customerDetailDTO;
  }

  @Override
  public CustomerDetailDTO updateStripeCustomer(String accountIdentifier, String customerId, CustomerDTO customerDTO) {
    isSelfServiceEnable(accountIdentifier);

    StripeCustomer stripeCustomer =
        stripeCustomerRepository.findByAccountIdentifierAndCustomerId(accountIdentifier, customerId);
    if (stripeCustomer == null) {
      throw new InvalidRequestException("Customer doesn't exists");
    }

    CustomerParamsBuilder builder = CustomerParams.builder();
    builder.customerId(stripeCustomer.getCustomerId());
    if (EmailValidator.getInstance().isValid(customerDTO.getBillingEmail())) {
      builder.billingContactEmail(customerDTO.getBillingEmail());
    }

    if (!Strings.isNullOrEmpty(customerDTO.getCompanyName())) {
      builder.name(customerDTO.getCompanyName());
    }
    CustomerDetailDTO customerDetailDTO = stripeHelper.updateCustomer(builder.build());

    // Update customer information at local After succeed
    saveCustomerLocally(accountIdentifier, stripeCustomer.getId(), customerDetailDTO);
    return customerDetailDTO;
  }

  @Override
  public CustomerDetailDTO getStripeCustomer(String accountIdentifier) {
    isSelfServiceEnable(accountIdentifier);

    StripeCustomer stripeCustomer = stripeCustomerRepository.findByAccountIdentifier(accountIdentifier);
    if (stripeCustomer == null) {
      throw new InvalidRequestException("Customer doesn't exists");
    }

    return stripeHelper.getCustomer(stripeCustomer.getCustomerId());
  }

  @Override
  public CustomerDetailDTO updateStripeBilling(String accountIdentifier, StripeBillingDTO stripeBillingDTO) {
    isSelfServiceEnable(accountIdentifier);

    StripeCustomer stripeCustomer = stripeCustomerRepository.findByAccountIdentifier(accountIdentifier);
    if (stripeCustomer == null) {
      throw new InvalidRequestException("Customer doesn't exists");
    }

    BillingParams params = BillingParams.builder().build();
    params.setLine1(stripeBillingDTO.getLine1());
    params.setLine2(stripeBillingDTO.getLine2());
    params.setCity(stripeBillingDTO.getCity());
    params.setState(stripeBillingDTO.getState());
    params.setCountry(stripeBillingDTO.getCountry());
    params.setZipCode(stripeBillingDTO.getZipCode());

    params.setCreditCardId(stripeBillingDTO.getCreditCardId());

    params.setCustomerId(stripeCustomer.getCustomerId());

    return stripeHelper.updateBilling(params);
  }

  //  @Override
  //  public List<CustomerDetailDTO> listStripeCustomers(String accountIdentifier) {
  //    isSelfServiceEnable(accountIdentifier);
  //
  //    // TODO: Might not needed any more due to one customer to one account
  //    List<StripeCustomer> stripeCustomers = stripeCustomerRepository.findByAccountIdentifier(accountIdentifier);
  //    return stripeCustomers.stream().map(s -> toCustomerDetailDTO(s)).collect(Collectors.toList());
  //  }

  @Override
  public PaymentMethodCollectionDTO listPaymentMethods(String accountIdentifier) {
    isSelfServiceEnable(accountIdentifier);

    // TODO: Might not needed any more because we request every time user input a payment method
    StripeCustomer stripeCustomer = stripeCustomerRepository.findByAccountIdentifier(accountIdentifier);
    if (stripeCustomer == null) {
      throw new InvalidRequestException("Customer doesn't exists");
    }
    return stripeHelper.listPaymentMethods(stripeCustomer.getCustomerId());
  }

  @Override
  public void syncStripeEvent(String eventString) {
    Event event = ApiResource.GSON.fromJson(eventString, Event.class);
    StripeEventHandler stripeEventHandler = eventHandlers.get(event.getType());
    if (stripeEventHandler == null) {
      throw new InvalidRequestException("Event type is not supported");
    }
    stripeEventHandler.handleEvent(event);
  }

  private void saveCustomerLocally(String accountIdentifier, String id, CustomerDetailDTO customerDetailDTO) {
    stripeCustomerRepository.save(StripeCustomer.builder()
                                      .id(id)
                                      .accountIdentifier(accountIdentifier)
                                      .billingEmail(customerDetailDTO.getBillingEmail())
                                      .companyName(customerDetailDTO.getCompanyName())
                                      .customerId(customerDetailDTO.getCustomerId())
                                      .build());
  }

  private boolean checkSubscriptionInValid(SubscriptionDetail subscriptionDetail, String accountIdentifier) {
    return subscriptionDetail == null || !subscriptionDetail.getAccountIdentifier().equals(accountIdentifier);
  }

  private void isSelfServiceEnable(String accountIdentifier) {
    if (!nGFeatureFlagHelperService.isEnabled(accountIdentifier, FeatureName.SELF_SERVICE_ENABLED)) {
      throw new UnsupportedOperationException("Self Service is currently unavailable");
    }
  }
}
