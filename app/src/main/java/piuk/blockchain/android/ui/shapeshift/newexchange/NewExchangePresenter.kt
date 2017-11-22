package piuk.blockchain.android.ui.shapeshift.newexchange

import info.blockchain.api.data.UnspentOutputs
import info.blockchain.wallet.api.data.FeeOptions
import info.blockchain.wallet.payload.data.Account
import info.blockchain.wallet.shapeshift.ShapeShiftPairs
import info.blockchain.wallet.shapeshift.data.MarketInfo
import info.blockchain.wallet.shapeshift.data.Quote
import info.blockchain.wallet.shapeshift.data.QuoteRequest
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import org.web3j.utils.Convert
import piuk.blockchain.android.R
import piuk.blockchain.android.data.cache.DynamicFeeCache
import piuk.blockchain.android.data.currency.CryptoCurrencies
import piuk.blockchain.android.data.currency.CurrencyState
import piuk.blockchain.android.data.datamanagers.FeeDataManager
import piuk.blockchain.android.data.ethereum.EthDataManager
import piuk.blockchain.android.data.payload.PayloadDataManager
import piuk.blockchain.android.data.payments.SendDataManager
import piuk.blockchain.android.data.rxjava.RxUtil
import piuk.blockchain.android.data.shapeshift.CoinPairings
import piuk.blockchain.android.data.shapeshift.ShapeShiftDataManager
import piuk.blockchain.android.data.stores.Either
import piuk.blockchain.android.ui.base.BasePresenter
import piuk.blockchain.android.ui.customviews.ToastCustom
import piuk.blockchain.android.ui.receive.ReceiveCurrencyHelper
import piuk.blockchain.android.ui.receive.WalletAccountHelper
import piuk.blockchain.android.ui.shapeshift.models.ShapeShiftData
import piuk.blockchain.android.util.ExchangeRateFactory
import piuk.blockchain.android.util.MonetaryUtil
import piuk.blockchain.android.util.PrefsUtil
import piuk.blockchain.android.util.StringUtils
import piuk.blockchain.android.util.helperfunctions.unsafeLazy
import timber.log.Timber
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParseException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class NewExchangePresenter @Inject constructor(
        private val payloadDataManager: PayloadDataManager,
        private val ethDataManager: EthDataManager,
        private val prefsUtil: PrefsUtil,
        private val sendDataManager: SendDataManager,
        private val dynamicFeeCache: DynamicFeeCache,
        private val feeDataManager: FeeDataManager,
        private val exchangeRateFactory: ExchangeRateFactory,
        private val currencyState: CurrencyState,
        private val shapeShiftDataManager: ShapeShiftDataManager,
        private val stringUtils: StringUtils,
        walletAccountHelper: WalletAccountHelper
) : BasePresenter<NewExchangeView>() {

    internal val toCryptoSubject: PublishSubject<String> = PublishSubject.create<String>()
    internal val fromCryptoSubject: PublishSubject<String> = PublishSubject.create<String>()
    internal val toFiatSubject: PublishSubject<String> = PublishSubject.create<String>()
    internal val fromFiatSubject: PublishSubject<String> = PublishSubject.create<String>()

    private val ethLabel = stringUtils.getString(R.string.shapeshift_eth)
    private val btcAccounts = walletAccountHelper.getHdAccounts()
    private val monetaryUtil by unsafeLazy {
        MonetaryUtil(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC))
    }
    private val currencyHelper by unsafeLazy {
        ReceiveCurrencyHelper(monetaryUtil, Locale.getDefault(), prefsUtil, exchangeRateFactory, currencyState)
    }
    private val cryptoFormat by unsafeLazy {
        (NumberFormat.getInstance(view.locale) as DecimalFormat).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 8
        }
    }
    private var marketInfo: MarketInfo? = null
    private var shapeShiftData: ShapeShiftData? = null
    private var latestQuote: Quote? = null
    private var account: Account? = null
    private var feeOptions: FeeOptions? = null

    override fun onViewReady() {
        val selectedCurrency = currencyState.cryptoCurrency
        view.updateUi(
                selectedCurrency,
                if (selectedCurrency == CryptoCurrencies.BTC) getBtcLabel() else ethLabel,
                if (selectedCurrency == CryptoCurrencies.ETHER) getBtcLabel() else ethLabel,
                monetaryUtil.getFiatDisplayString(0.0, currencyHelper.fiatUnit, Locale.getDefault())
        )

        val shapeShiftObservable = getMarketInfoObservable(selectedCurrency)
        val feesObservable = fetchFeesObservable(selectedCurrency)

        shapeShiftObservable
                .doOnNext { marketInfo = it }
                .flatMap { feesObservable }
                .doOnSubscribe { view.showProgressDialog(R.string.shapeshift_getting_information) }
                .doOnTerminate { view.dismissProgressDialog() }
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe(
                        // Only set account the first time
                        { if (account == null) account = payloadDataManager.defaultAccount },
                        {
                            Timber.e(it)
                            view.showToast(R.string.shapeshift_getting_information_failed, ToastCustom.TYPE_ERROR)
                            view.finishPage()
                        }
                )

        fromCryptoSubject.applyDefaults()
                // Update to Fiat as it's not dependent on web results
                .doOnNext { updateFromFiat(it) }
                // Update results dependent on Shapeshift
                .flatMap { amount ->
                    getQuoteFromRequest(amount, currencyState.cryptoCurrency)
                            .doOnNext { updateToFields(it.withdrawalAmount) }
                }
                .subscribe()

        fromFiatSubject.applyDefaults()
                // Convert to fromCrypto amount
                .map {
                    val (_, toExchangeRate) = getExchangeRates()
                    return@map it.divide(toExchangeRate, 18, RoundingMode.HALF_UP)
                }
                // Update from amount view
                .doOnNext { view.updateFromCryptoText(cryptoFormat.format(it)) }
                // Update results dependent on Shapeshift
                .flatMap { amount ->
                    getQuoteFromRequest(amount, currencyState.cryptoCurrency)
                            .doOnNext { updateToFields(it.withdrawalAmount) }
                }
                .subscribe()

        toCryptoSubject.applyDefaults()
                // Update to Fiat as it's not dependent on web results
                .doOnNext { updateToFiat(it) }
                // Update results dependent on Shapeshift
                .flatMap { amount ->
                    getQuoteToRequest(amount, currencyState.cryptoCurrency)
                            .doOnNext { updateFromFields(it.depositAmount) }
                }
                .subscribe()

        toFiatSubject.applyDefaults()
                // Convert to toCrypto amount
                .map {
                    val (fromExchangeRate, _) = getExchangeRates()
                    return@map it.divide(fromExchangeRate, 18, RoundingMode.HALF_UP)
                }
                // Update from amount view
                .doOnNext { view.updateToCryptoText(cryptoFormat.format(it)) }
                // Update results dependent on Shapeshift
                .flatMap { amount ->
                    getQuoteToRequest(amount, currencyState.cryptoCurrency)
                            .doOnNext { updateFromFields(it.depositAmount) }
                }
                .subscribe()
    }

    internal fun onSwitchCurrencyClicked() {
        currencyState.toggleCryptoCurrency()
                .run { compositeDisposable.clear() }
                .run { view.clearEditTexts() }
                .run {
                    // This is a bit hacky and should be abstracted out when more currencies are
                    // available, hence the null checks in onViewReady().
                    onViewReady()
                }
    }

    internal fun onContinuePressed() {
        // State check
        check(shapeShiftData != null) { "ShapeShiftData is null, presenter state invalid" }
        // Check user isn't submitting an empty page
        if (shapeShiftData?.withdrawalAmount == BigDecimal.ZERO) {
            view.showToast(R.string.invalid_amount, ToastCustom.TYPE_ERROR)
            return
        }

        // It's possible that the fee observable can return zero but not kill the chain with an
        // error, hence checking here
        if (shapeShiftData?.networkFee == BigDecimal.ZERO) {
            view.showToast(R.string.shapeshift_getting_fees_failed, ToastCustom.TYPE_ERROR)
            return
        }

        val selectedCurrency = currencyState.cryptoCurrency

        getMaxCurrencyObservable()
                .doOnSubscribe { view.showProgressDialog(R.string.please_wait) }
                .doOnNext {
                    val amount = it.setScale(8, RoundingMode.HALF_DOWN)
                    if (amount < shapeShiftData!!.depositAmount) {
                        view.dismissProgressDialog()
                        // Show warning, inform user
                        view.showAmountError(stringUtils.getString(R.string.insufficient_funds))

                        Timber.d("Attempted to send ${shapeShiftData!!.depositAmount} but max available was $amount")
                    } else {
                        sendFinalRequest(selectedCurrency)
                    }
                }.subscribe()
    }

    internal fun onMaxPressed() {
        view.removeAllFocus()
        view.showQuoteInProgress(true)
        getMaxCurrencyObservable().subscribe(
                {
                    fromCryptoSubject.onNext(cryptoFormat.format(it))
                    // This is a bit of a hack to bypass focus issues
                    view.updateFromCryptoText(cryptoFormat.format(it))
                },
                { Timber.e(it) }
        )
    }

    internal fun onMinPressed() {
        view.removeAllFocus()
        view.showQuoteInProgress(true)
        with(getMinimum()) {
            fromCryptoSubject.onNext(cryptoFormat.format(this))
            // This is a bit of a hack to bypass focus issues
            view.updateFromCryptoText(cryptoFormat.format(this))
        }
    }

    internal fun onFromChooserClicked() = view.launchAccountChooserActivityFrom()

    internal fun onToChooserClicked() = view.launchAccountChooserActivityTo()

    internal fun onFromEthSelected() {
        currencyState.cryptoCurrency = CryptoCurrencies.ETHER
        view.clearEditTexts()
        updateUi(ethLabel, account!!.label)
    }

    internal fun onToEthSelected() {
        currencyState.cryptoCurrency = CryptoCurrencies.BTC
        view.clearEditTexts()
        updateUi(account!!.label, ethLabel)
    }

    // Here we can safely assume BTC is the "from" type
    internal fun onFromAccountChanged(account: Account) {
        currencyState.cryptoCurrency = CryptoCurrencies.BTC
        this.account = account
        view.clearEditTexts()
        updateUi(account.label, ethLabel)
    }

    // Here we can safely assume ETH is the "from" type
    internal fun onToAccountChanged(account: Account) {
        currencyState.cryptoCurrency = CryptoCurrencies.ETHER
        this.account = account
        view.clearEditTexts()
        updateUi(ethLabel, account.label)
    }

    private fun getExchangeRates(): ExchangeRates {
        val currencyCode = currencyHelper.fiatUnit
        return when (currencyState.cryptoCurrency) {
            CryptoCurrencies.BTC -> ExchangeRates(
                    BigDecimal.valueOf(exchangeRateFactory.getLastEthPrice(currencyCode)),
                    BigDecimal.valueOf(exchangeRateFactory.getLastBtcPrice(currencyCode))
            )
            CryptoCurrencies.ETHER -> ExchangeRates(
                    BigDecimal.valueOf(exchangeRateFactory.getLastBtcPrice(currencyCode)),
                    BigDecimal.valueOf(exchangeRateFactory.getLastEthPrice(currencyCode))
            )
            else -> throw IllegalArgumentException("BCH is not currently supported")
        }
    }

    private fun getUnspentApiResponse(address: String): Observable<UnspentOutputs> {
        return if (payloadDataManager.getAddressBalance(address).toLong() > 0) {
            sendDataManager.getUnspentOutputs(address)
        } else {
            Observable.error(Throwable("No funds - skipping call to unspent API"))
        }
    }

    private fun getBtcLabel() = if (btcAccounts.size > 1) {
        if (account != null) {
            account!!.label
        } else {
            payloadDataManager.defaultAccount.label
        }
    } else {
        stringUtils.getString(R.string.shapeshift_btc)
    }

    private fun getShapeShiftPair(selectedCurrency: CryptoCurrencies) = when (selectedCurrency) {
        CryptoCurrencies.BTC -> ShapeShiftPairs.BTC_ETH
        else -> ShapeShiftPairs.ETH_BTC
    }

    private fun getMaximum() = BigDecimal.valueOf(marketInfo?.maxLimit ?: 0.0)

    private fun getMinimum() = BigDecimal.valueOf(marketInfo?.minimum ?: 0.0)

    //region Field Updates
    private fun updateUi(fromLabel: String, toLabel: String) {
        view.updateUi(
                currencyState.cryptoCurrency,
                fromLabel,
                toLabel,
                monetaryUtil.getFiatDisplayString(0.0, currencyHelper.fiatUnit, Locale.getDefault())
        )
    }

    private fun updateFromFiat(amount: BigDecimal) {
        view.updateFromFiatText(
                monetaryUtil.getFiatDisplayString(
                        amount.multiply(getExchangeRates().fromRate).toDouble(),
                        currencyHelper.fiatUnit,
                        view.locale
                )
        )
    }

    private fun updateToFiat(amount: BigDecimal) {
        view.updateToFiatText(
                monetaryUtil.getFiatDisplayString(
                        amount.multiply(getExchangeRates().toRate).toDouble(),
                        currencyHelper.fiatUnit,
                        view.locale
                )
        )
    }

    private fun updateToFields(toAmount: Double) {
        val amount = toAmount.toBigDecimal().max(BigDecimal.ZERO)
        view.updateToCryptoText(cryptoFormat.format(amount))
        updateToFiat(amount)
    }

    private fun updateFromFields(fromAmount: Double) {
        val amount = fromAmount.toBigDecimal().max(BigDecimal.ZERO)
        view.updateFromCryptoText(cryptoFormat.format(amount))
        updateFromFiat(amount)
    }
    //endregion

    //region Observables
    /**
     * Sends a complete [QuoteRequest] object to ShapeShift and sends all of the required fields
     * serialized to the next Activity.
     */
    private fun sendFinalRequest(selectedCurrency: CryptoCurrencies) {
        val quoteRequest = QuoteRequest().apply {
            depositAmount = shapeShiftData!!.depositAmount.toDouble()
            withdrawalAmount = shapeShiftData!!.withdrawalAmount.toDouble()
            withdrawal = shapeShiftData!!.withdrawalAddress
            pair = getShapeShiftPair(selectedCurrency)
            returnAddress = shapeShiftData!!.returnAddress
            apiKey = view.shapeShiftApiKey
        }
        // Update quote with final data
        getQuoteObservable(quoteRequest, selectedCurrency)
                .doOnTerminate { view.dismissProgressDialog() }
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe(
                        { view.launchConfirmationPage(shapeShiftData!!) },
                        {
                            Timber.e(it)
                            view.showToast(R.string.unexpected_error, ToastCustom.TYPE_ERROR)
                        }
                )
    }

    private fun getQuoteFromRequest(
            fromAmount: BigDecimal,
            selectedCurrency: CryptoCurrencies
    ): Observable<Quote> {
        val quoteRequest = QuoteRequest().apply {
            depositAmount = fromAmount.setScale(8, RoundingMode.HALF_DOWN).toDouble()
            pair = getShapeShiftPair(selectedCurrency)
            apiKey = view.shapeShiftApiKey
        }

        return getQuoteObservable(quoteRequest, selectedCurrency)
    }

    private fun getQuoteToRequest(
            toAmount: BigDecimal,
            selectedCurrency: CryptoCurrencies
    ): Observable<Quote> {
        val quoteRequest = QuoteRequest().apply {
            withdrawalAmount = toAmount.setScale(8, RoundingMode.HALF_DOWN).toDouble()
            pair = getShapeShiftPair(selectedCurrency)
            apiKey = view.shapeShiftApiKey
        }

        return getQuoteObservable(quoteRequest, selectedCurrency)
    }

    private fun fetchFeesObservable(selectedCurrency: CryptoCurrencies) = when (selectedCurrency) {
        CryptoCurrencies.BTC -> feeDataManager.btcFeeOptions
                .doOnSubscribe { feeOptions = dynamicFeeCache.btcFeeOptions!! }
                .doOnNext { dynamicFeeCache.btcFeeOptions = it }

        CryptoCurrencies.ETHER -> feeDataManager.ethFeeOptions
                .doOnSubscribe { feeOptions = dynamicFeeCache.ethFeeOptions!! }
                .doOnNext { dynamicFeeCache.ethFeeOptions = it }

        else -> throw IllegalArgumentException("BCH is not currently supported")
    }

    private fun getMarketInfoObservable(selectedCurrency: CryptoCurrencies) = when (selectedCurrency) {
        CryptoCurrencies.BTC -> shapeShiftDataManager.getRate(CoinPairings.BTC_TO_ETH)
        CryptoCurrencies.ETHER -> shapeShiftDataManager.getRate(CoinPairings.ETH_TO_BTC)
        else -> throw IllegalArgumentException("BCH is not currently supported")
    }

    private fun getQuoteObservable(quoteRequest: QuoteRequest, selectedCurrency: CryptoCurrencies) =
            // Get quote for Quote Request
            shapeShiftDataManager.getQuote(quoteRequest)
                    .compose(RxUtil.addObservableToCompositeDisposable(this))
                    .map {
                        when (it) {
                            is Either.Right<Quote> -> return@map it.value
                            is Either.Left<String> -> {
                                // Show error in UI, fallback to initial quote rates
                                view.showAmountError(it.value)
                                return@map Quote().apply {
                                    orderId = ""
                                    quotedRate = marketInfo?.rate ?: 1.0
                                    minerFee = marketInfo?.minerFee ?: 0.0
                                    withdrawalAmount = quoteRequest.withdrawalAmount
                                    depositAmount = quoteRequest.depositAmount
                                    expiration = 0L
                                }
                            }
                        }
                    }
                    .flatMap { quote ->
                        // Get fee for the proposed payment amount
                        getFeeForPayment(quote.withdrawalAmount.toBigDecimal(), selectedCurrency)
                                .flatMap { fee ->
                                    // Get receive/change address pair
                                    getAddressPair(selectedCurrency)
                                            .map { addresses ->
                                                latestQuote = quote
                                                // Update ShapeShift Data
                                                shapeShiftData = ShapeShiftData(
                                                        orderId = quote.orderId,
                                                        fromCurrency = selectedCurrency,
                                                        toCurrency = if (selectedCurrency == CryptoCurrencies.BTC) CryptoCurrencies.ETHER else CryptoCurrencies.BTC,
                                                        depositAmount = quote.depositAmount.toBigDecimal(),
                                                        depositAddress = quote.deposit ?: "",
                                                        withdrawalAmount = quote.withdrawalAmount.toBigDecimal(),
                                                        withdrawalAddress = addresses.withdrawalAddress,
                                                        exchangeRate = quote.quotedRate.toBigDecimal(),
                                                        transactionFee = fee,
                                                        networkFee = quote.minerFee.toBigDecimal(),
                                                        returnAddress = addresses.returnAddress,
                                                        xPub = account!!.xpub,
                                                        expiration = quote.expiration,
                                                        gasLimit = BigInteger.valueOf(feeOptions?.gasLimit ?: 0L),
                                                        gasPrice = BigInteger.valueOf(feeOptions?.regularFee ?: 0L),
                                                        feePerKb = BigInteger.valueOf(feeOptions?.priorityFee ?: 0 * 1000)
                                                )

                                                return@map quote
                                            }
                                }
                    }
                    .doOnTerminate {
                        view.showQuoteInProgress(false)
                        view.setButtonEnabled(true)
                    }

    //region Fees Observables
    private fun getFeeForPayment(
            amountToSend: BigDecimal,
            selectedCurrency: CryptoCurrencies
    ): Observable<BigInteger> = when (selectedCurrency) {
        CryptoCurrencies.BTC -> getFeeForBtcPaymentObservable(
                amountToSend,
                BigInteger.valueOf(feeOptions!!.priorityFee * 1000)
        )
        CryptoCurrencies.ETHER -> getFeeForEthPaymentObservable()
        else -> throw IllegalArgumentException("BCH not yet supported")
    }.doOnError { view.showToast(R.string.confirm_payment_fee_sync_error, ToastCustom.TYPE_ERROR) }
            .onErrorReturn { BigInteger.ZERO }

    /**
     * Returns the ETH fee in Wei
     */
    private fun getFeeForEthPaymentObservable(): Observable<BigInteger> {
        val gwei = BigDecimal.valueOf(feeOptions!!.gasLimit * feeOptions!!.regularFee)
        val feeInGwei = Convert.toWei(gwei, Convert.Unit.GWEI)
        return Observable.just(feeInGwei.toBigInteger())
    }

    /**
     * Returns the BTC fee in Satoshis
     */
    private fun getFeeForBtcPaymentObservable(
            amountToSend: BigDecimal,
            feePerKb: BigInteger
    ): Observable<BigInteger> = getUnspentApiResponse(account!!.xpub)
            .compose(RxUtil.addObservableToCompositeDisposable(this))
            .map {
                val satoshis = amountToSend.multiply(BigDecimal.valueOf(100000000))
                return@map sendDataManager.getSpendableCoins(
                        it,
                        satoshis.toBigInteger(),
                        feePerKb
                ).absoluteFee
            }
    //endregion

    //region Address Pair Observables
    private fun getAddressPair(selectedCurrency: CryptoCurrencies): Observable<Addresses> =
            when (selectedCurrency) {
                CryptoCurrencies.BTC -> getBtcReceiveAddress()
                        .flatMap { returnAddress ->
                            getEthAddress()
                                    .map { Addresses(it, returnAddress) }
                        }
                CryptoCurrencies.ETHER -> getEthAddress()
                        .flatMap { returnAddress ->
                            getBtcReceiveAddress()
                                    .map { Addresses(it, returnAddress) }
                        }
                else -> throw IllegalArgumentException("BCH not yet supported")
            }.doOnError { view.showToast(R.string.shapeshift_deriving_address_failed, ToastCustom.TYPE_ERROR) }

    private fun getEthAddress(): Observable<String> =
            Observable.just(ethDataManager.getEthWallet()!!.account.address)

    private fun getBtcReceiveAddress(): Observable<String> =
            payloadDataManager.getNextReceiveAddress(account!!)
    //endregion

    //region Max Amounts Observables
    private fun getMaxCurrencyObservable(): Observable<BigDecimal> =
            when (currencyState.cryptoCurrency) {
                CryptoCurrencies.BTC -> getBtcMaxObservable()
                CryptoCurrencies.ETHER -> getEthMaxObservable()
                else -> throw IllegalArgumentException("BCH is not currently supported")
            }

    private fun getEthMaxObservable(): Observable<BigDecimal> = ethDataManager.fetchEthAddress()
            .compose(RxUtil.addObservableToCompositeDisposable(this))
            .doOnError { view.showToast(R.string.shapeshift_fetching_eth_account_failed, ToastCustom.TYPE_ERROR) }
            .map {
                val gwei = BigDecimal.valueOf(feeOptions!!.gasLimit * feeOptions!!.regularFee)
                val wei = Convert.toWei(gwei, Convert.Unit.GWEI)

                val addressResponse = it.getAddressResponse()
                val maxAvailable = addressResponse!!.balance!!.minus(wei.toBigInteger()).max(BigInteger.ZERO)

                val availableEth = Convert.fromWei(maxAvailable.toString(), Convert.Unit.ETHER)
                return@map if (availableEth > getMaximum()) getMaximum() else availableEth
            }

    private fun getBtcMaxObservable(): Observable<BigDecimal> = getUnspentApiResponse(account!!.xpub)
            .compose(RxUtil.addObservableToCompositeDisposable(this))
            .doOnError { view.showToast(R.string.shapeshift_fetching_unspent_outputs_failed, ToastCustom.TYPE_ERROR) }
            .map {
                val sweepBundle = sendDataManager.getMaximumAvailable(
                        it,
                        BigInteger.valueOf(feeOptions!!.priorityFee * 1000)
                )
                val sweepableAmount = BigDecimal(sweepBundle.left).divide(BigDecimal.valueOf(1e8))
                return@map if (sweepableAmount > getMaximum()) getMaximum() else sweepableAmount
            }
    //endregion

    private fun PublishSubject<String>.applyDefaults(): Observable<BigDecimal> = this.doOnNext {
        view.clearError()
        view.setButtonEnabled(false)
        view.showQuoteInProgress(true)
    }.debounce(500, TimeUnit.MILLISECONDS)
            // Here we kill any quotes in flight already, as they take up to ten seconds to fulfill
            .doOnNext { compositeDisposable.clear() }
            .map { it.sanitise().parse(view.locale) }
            .doOnError(Timber::e)
            .onErrorReturn { BigDecimal.ZERO }
            .compose(RxUtil.applySchedulersToObservable())
    //endregion

    private fun String.sanitise() = if (isNotEmpty()) this else "0"

    private fun Double.toBigDecimal() = BigDecimal.valueOf(this)

    @Throws(ParseException::class)
    private fun String.parse(locale: Locale): BigDecimal {
        val format = NumberFormat.getNumberInstance(locale)
        if (format is DecimalFormat) {
            format.isParseBigDecimal = true
        }
        return format.parse(this.replace("[^\\d.,]".toRegex(), "")) as BigDecimal
    }

    private data class ExchangeRates(val toRate: BigDecimal, val fromRate: BigDecimal)

    private data class Addresses(val withdrawalAddress: String, val returnAddress: String)

}