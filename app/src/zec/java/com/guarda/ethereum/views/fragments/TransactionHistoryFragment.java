package com.guarda.ethereum.views.fragments;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.widget.NestedScrollView;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.clans.fab.FloatingActionButton;
import com.github.clans.fab.FloatingActionMenu;
import com.guarda.ethereum.GuardaApp;
import com.guarda.ethereum.R;
import com.guarda.ethereum.lifecycle.HistoryViewModel;
import com.guarda.ethereum.managers.CoinmarketcapHelper;
import com.guarda.ethereum.managers.NetworkManager;
import com.guarda.ethereum.managers.SharedManager;
import com.guarda.ethereum.managers.TransactionsManager;
import com.guarda.ethereum.managers.WalletManager;
import com.guarda.ethereum.models.constants.Common;
import com.guarda.ethereum.models.items.BtgBalanceResponse;
import com.guarda.ethereum.models.items.RespExch;
import com.guarda.ethereum.models.items.TokenBodyItem;
import com.guarda.ethereum.models.items.TokenHeaderItem;
import com.guarda.ethereum.rest.ApiMethods;
import com.guarda.ethereum.rest.RequestorBtc;
import com.guarda.ethereum.views.activity.MainActivity;
import com.guarda.ethereum.views.activity.TransactionDetailsActivity;
import com.guarda.ethereum.views.adapters.TokenAdapter;
import com.guarda.ethereum.views.adapters.TransHistoryAdapter;
import com.guarda.ethereum.views.fragments.base.BaseFragment;
import com.guarda.zcash.sapling.SyncManager;
import com.guarda.zcash.sapling.db.DbManager;
import com.guarda.zcash.sapling.rxcall.CallSaplingBalance;
import com.thoughtbot.expandablerecyclerview.models.ExpandableGroup;

import org.bitcoinj.core.Coin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import autodagger.AutoInjector;
import butterknife.BindView;
import butterknife.OnClick;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

import static com.guarda.ethereum.models.constants.Common.BLOCK;
import static com.guarda.ethereum.models.constants.Common.EXTRA_TRANSACTION_POSITION;
import static com.guarda.ethereum.models.constants.Extras.CREATE_WALLET;
import static com.guarda.ethereum.models.constants.Extras.FIRST_ACTION_MAIN_ACTIVITY;
import static com.guarda.ethereum.models.constants.Extras.KEY;

@AutoInjector(GuardaApp.class)
public class TransactionHistoryFragment extends BaseFragment {

    @BindView(R.id.tv_wallet_count)
    TextView tvCryptoCount;
    @BindView(R.id.tv_wallet_usd_count)
    TextView tvUSDCount;
    @BindView(R.id.fab_menu)
    FloatingActionMenu fabMenu;
    @BindView(R.id.fab_buy)
    FloatingActionButton fabBuy;
    @BindView(R.id.fab_purchase)
    FloatingActionButton fabPurchase;
    @BindView(R.id.fab_deposit)
    FloatingActionButton fabDeposit;
    @BindView(R.id.fab_withdraw)
    FloatingActionButton fabWithdraw;
    @BindView(R.id.iv_update_transactions)
    ImageView tvUpdateTransactions;
    @BindView(R.id.rv_transactions_list)
    RecyclerView rvTransactionsList;
    @BindView(R.id.sv_main_scroll_layout)
    NestedScrollView nsvMainScrollLayout;
    @BindView(R.id.swipeRefresh)
    SwipeRefreshLayout swipeRefreshLayout;
    @BindView(R.id.rv_tokens)
    RecyclerView rvTokens;

    private boolean isVisible = true;
    private boolean stronglyHistory = false;
    private ObjectAnimator loaderAnimation;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private HistoryViewModel historyViewModel;
    private TransHistoryAdapter adapter;
    private TokenAdapter tokenAdapter;
    private List<TokenBodyItem> tokensList = new ArrayList<>();
    private String exchangeRate;
    private final String tAddrTitle = "T-address";
    private final String zAddrTitle = "Z-address";
    private final String statusSyncing = "Syncing...";
    private final String statusSycned = "Synced";
    private Long transparentBalance;
    private Long saplingBalance;

    @Inject
    WalletManager walletManager;
    @Inject
    TransactionsManager transactionsManager;
    @Inject
    SharedManager sharedManager;
    @Inject
    SyncManager syncManager;
    @Inject
    DbManager dbManager;

    public TransactionHistoryFragment() {
        GuardaApp.getAppComponent().inject(this);
    }

    @Override
    protected int getLayout() {
        return R.layout.fragment_transaction_history;
    }

    @Override
    protected void init() {
        HistoryViewModel.Factory factory = new HistoryViewModel.Factory(walletManager, transactionsManager, dbManager, syncManager);
        historyViewModel = ViewModelProviders.of(this, factory).get(HistoryViewModel.class);
        subscribeUi();

        stronglyHistory = true;

        initTransactionHistoryRecycler();

        tokensList.add(new TokenBodyItem(tAddrTitle, new BigDecimal("0"), "0", 8));
        tokensList.add(new TokenBodyItem(zAddrTitle, new BigDecimal("0"), "0", 8));
        initTokens(tokensList);

        nsvMainScrollLayout.smoothScrollTo(0, 0);

        fabMenu.setClosedOnTouchOutside(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            initFabHider();
        }

        initRotation(tvUpdateTransactions);
        initMenuButton();

        swipeRefreshLayout.setProgressViewEndTarget(false, -2000);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            swipeRefreshLayout.setRefreshing(false);
            updBalanceHistSync();
        });

        String firstAction = null;
        if (getArguments() != null) {
            firstAction = getArguments().getString(FIRST_ACTION_MAIN_ACTIVITY);
        }
        if (firstAction != null && firstAction.equalsIgnoreCase(CREATE_WALLET)) {
            if (TextUtils.isEmpty(walletManager.getWalletFriendlyAddress())) {
                createWallet(BLOCK);
            }
        } else {
            checkFromRestore();
        }

        updBalanceHistSync();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void initFabHider() {
        nsvMainScrollLayout.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                if (scrollY > oldScrollY) {
                    fabMenu.setVisibility(View.GONE);
                } else {
                    fabMenu.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void initTokens(List<TokenBodyItem> tokens) {
        Timber.d("initTokens start");
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        RecyclerView.ItemAnimator animator = rvTokens.getItemAnimator();
        if (animator instanceof DefaultItemAnimator) {
            ((DefaultItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        tokenAdapter = new TokenAdapter(generateTokensGroup(tokens));
        rvTokens.setLayoutManager(layoutManager);
        rvTokens.setAdapter(tokenAdapter);
        Timber.d("initTokens end");
    }

    private List<? extends ExpandableGroup> generateTokensGroup(List<TokenBodyItem> tokenBodyItems) {
        String h = "";
        if (isAdded()) h = getString(R.string.own_addresses);
        return Arrays.asList(
                new TokenHeaderItem(h, tokenBodyItems, "2")
        );
    }

    private void updBalanceHistSync() {
        if (isWalletExist()) {
            showBalance();
            historyViewModel.startSync();
        }
    }

    private void createWallet(String passphrase) {
        showProgress(getStringIfAdded(R.string.generating_wallet));
        walletManager.createWallet(passphrase, () -> {
                closeProgress();
                openUserWalletFragment();
        });
    }

    private void updateFromDbOrEmpty() {
        if (transactionsManager.getTransactionsList().size() == 0) {
            GuardaApp.isTransactionsEmpty = true;
            openUserWalletFragment();
        } else {
            GuardaApp.isTransactionsEmpty = false;
            historyViewModel.getTxsFromDb();
        }
    }

    private void openUserWalletFragment() {
        navigateToFragment(new UserWalletFragment());
    }

    private boolean isWalletExist() {
        return !TextUtils.isEmpty(walletManager.getWalletFriendlyAddress());
    }

    private void showBalance() {
        if (isAdded() && !isDetached() && isVisible && NetworkManager.isOnline(getActivity())) {
            startClockwiseRotation();
            loadBalance();
            historyViewModel.loadTransactions();
        } else {
            if (getActivity() != null) {
                ((MainActivity) getActivity()).showCustomToast(getStringIfAdded(R.string.err_network), R.drawable.err_network);
            }
        }
    }

    private void loadBalance() {
        RequestorBtc.getBalanceZecNew(walletManager.getWalletFriendlyAddress(), new ApiMethods.RequestListener() {
            @Override
            public void onSuccess(Object response) {
                BtgBalanceResponse balance = (BtgBalanceResponse) response;
                transparentBalance = balance.getBalanceSat();
                walletManager.setMyBalance(balance.getBalanceSat());
                walletManager.setBalance(balance.getBalanceSat());
                String curBalance = WalletManager.getFriendlyBalance(walletManager.getMyBalance());
                setCryptoBalance();
                tokensList.set(0, new TokenBodyItem(tAddrTitle, new BigDecimal(curBalance), curBalance, 8));
                tokenAdapter.notifyDataSetChanged();
                //FIXME: get usd balance
                getLocalBalance(curBalance);
            }

            @Override
            public void onFailure(String msg) {
                if (getActivity() != null) {
                    ((MainActivity) getActivity()).showCustomToast(getStringIfAdded(R.string.err_get_balance), R.drawable.err_balance);
                }
            }
        });

        compositeDisposable.add(Observable
                .fromCallable(new CallSaplingBalance(dbManager))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((balance) -> {
                    Timber.d("CallSaplingBalance balance=%d", balance);

                    if (balance == null) return;
                    saplingBalance = balance;
                    setCryptoBalance();
                    tokensList.set(1, new TokenBodyItem(zAddrTitle, new BigDecimal(Coin.valueOf(balance).toPlainString()), Coin.valueOf(balance).toPlainString(), 8));
                    tokenAdapter.notifyDataSetChanged();
                }));
    }

    private void getLocalBalance(final String balance) {
        CoinmarketcapHelper.getExchange(Common.MAIN_CURRENCY_NAME,
                sharedManager.getLocalCurrency().toLowerCase(),
                new ApiMethods.RequestListener() {
                    @Override
                    public void onSuccess(Object response) {
                        List<RespExch> exchange = (List<RespExch>) response;

                        String localBalance = balance.replace(",", "");

                        exchangeRate = exchange.get(0).getPrice(sharedManager.getLocalCurrency().toLowerCase());
                        if (exchangeRate != null) updateUsdBalances();
                        setUSDBalance();
                    }

                    @Override
                    public void onFailure(String msg) {
                        Timber.d("CoinmarketcapHelper.getExchange onFailure=%s", msg);
                    }
                });
    }

    private void updateUsdBalances() {
        Double sum = 0d;
        for (TokenBodyItem tb : tokensList) {
            if (tb.getTokenNum() == null ||
                    tb.getTokenNum().compareTo(BigDecimal.ZERO) == 0) continue;

            Double res = Double.valueOf(tb.getTokenNum().toString()) * (Double.valueOf(exchangeRate));
            sum += res;
            tb.setOtherSum(res);
        }
        tokenAdapter.notifyDataSetChanged();
    }

    public static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private void checkFromRestore() {
        Bundle args = getArguments();
        if (args != null) {
            String key = args.getString(KEY);
            if (!TextUtils.isEmpty(key)) {
                showProgress(getStringIfAdded(R.string.restoring_wallet));
                historyViewModel.restoreWallet(key);
            }
        }
    }

    private void initTransactionHistoryRecycler() {
        final LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        rvTransactionsList.setLayoutManager(layoutManager);

        adapter = new TransHistoryAdapter();
        adapter.setItemClickListener((position) -> {
                Intent detailsIntent = new Intent(getActivity(), TransactionDetailsActivity.class);
                detailsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                detailsIntent.putExtra(EXTRA_TRANSACTION_POSITION, position);
                startActivity(detailsIntent);
                getActivity().overridePendingTransition(R.anim.slide_in_left, R.anim.no_slide);
        });

        rvTransactionsList.setAdapter(adapter);
    }

    private void setCryptoBalance() {
        Long tb = 0L;
        Long zb = 0L;
        if (transparentBalance != null) tb = transparentBalance;
        if (saplingBalance != null) zb = saplingBalance;

        long sum = tb + zb;

        tvCryptoCount.setText(String.format(Locale.US, "%s " + sharedManager.getCurrentCurrency().toUpperCase(),
                Coin.valueOf(sum).toPlainString()));
    }

    private void setUSDBalance() {
        Long tb = 0L;
        Long zb = 0L;
        if (transparentBalance != null) tb = transparentBalance;
        if (saplingBalance != null) zb = saplingBalance;

        long sum = tb + zb;

        double res = Double.valueOf(Coin.valueOf(sum).toPlainString()) * (Double.valueOf(exchangeRate));
        tvUSDCount.setText(String.format("%s %s", Double.toString(round(res, 2)), sharedManager.getLocalCurrency().toUpperCase()));

    }

    @OnClick({R.id.fab_buy, R.id.fab_purchase, R.id.fab_withdraw, R.id.fab_deposit})
    public void fabButtonsClick(View view) {
        MainActivity mainActivity = (MainActivity) getActivity();
        switch (view.getId()) {
            case R.id.fab_buy:
                fabMenu.close(true);
                navigateToFragment(new PurchaseServiceFragment());
                mainActivity.setToolBarTitle(R.string.app_amount_to_purchase);
                break;
            case R.id.fab_purchase:
                fabMenu.close(true);
                navigateToFragment(new ExchangeFragment());
                mainActivity.setToolBarTitle(R.string.purchase_purchase);
                break;
            case R.id.fab_withdraw:
                fabMenu.close(true);
                navigateToFragment(new WithdrawFragment());
                mainActivity.setToolBarTitle(R.string.withdraw_address_send);
                break;
            case R.id.fab_deposit:
                fabMenu.close(true);
                navigateToFragment(new DepositFragment());
                mainActivity.setToolBarTitle(R.string.app_your_address);
                break;
        }
    }

    @OnClick(R.id.iv_update_transactions)
    public void onUpdateClick() {
        updBalanceHistSync();
    }

    private void subscribeUi() {
        historyViewModel.getShowHistory().observe(getViewLifecycleOwner(), (v) -> {
            if (v) {
                updateFromDbOrEmpty();
                loaderAnimation.cancel();
            }
        });

        historyViewModel.getShowTxError().observe(getViewLifecycleOwner(), (v) -> {
            if (v) {
                loaderAnimation.cancel();
                ((MainActivity) getActivity()).showCustomToast(getStringIfAdded(R.string.err_get_history), R.drawable.err_history);
            }
        });

        historyViewModel.getShowActualTxs().observe(getViewLifecycleOwner(), (list) -> {
            adapter.updateList(list);
            adapter.notifyDataSetChanged();
//            loaderAnimation.cancel();
        });

        historyViewModel.getSyncInProgress().observe(getViewLifecycleOwner(), (t) -> {
            setSyncStatus(t);
            Timber.d("getSyncInProgress().observe t=%b", t);
        });

        historyViewModel.setCurrentStatus();

        historyViewModel.getIsRestored().observe(getViewLifecycleOwner(), (t) -> {

            closeProgress();
            updBalanceHistSync();

            Timber.d("getIsRestored().observe t=%b", t);
        });

    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        isVisible = true;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        isVisible = false;
    }

    @Override
    public void onStop() {
        super.onStop();
        compositeDisposable.clear();
    }

    private void setSyncStatus(boolean b) {
        setToolbarTitle(b ? statusSyncing : statusSycned);
    }

    private void initRotation(ImageView ivLoader) {
        if (loaderAnimation == null) {
            loaderAnimation = ObjectAnimator.ofFloat(ivLoader, "rotation", 0.0f, 360f);
            loaderAnimation.setDuration(1500);
            loaderAnimation.setRepeatCount(ObjectAnimator.INFINITE);
            loaderAnimation.setInterpolator(new LinearInterpolator());
            loaderAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationCancel(animation);
                    tvUpdateTransactions.setClickable(true);
                }
            });
        }
    }

    private void startClockwiseRotation() {
        if (!loaderAnimation.isRunning()) {
            loaderAnimation.start();
        }
    }

    public void scrollToTop() {
        nsvMainScrollLayout.scrollTo(0, 0);
    }

    private String getStringIfAdded(int resId) {
        if (isAdded()) {
            return getString(resId);
        } else {
            return "";
        }
    }

}
