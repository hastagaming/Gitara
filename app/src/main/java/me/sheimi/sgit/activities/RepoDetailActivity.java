package me.sheimi.sgit.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.ListView;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import io.gitara.ui.repodetail.RepoDetailComposeEntry;
import io.gitara.ui.repodetail.RepoDetailController;
import io.gitara.ui.repodetail.RepoDetailHost;
import me.sheimi.android.activities.SheimiFragmentActivity;
import me.sheimi.sgit.R;
import me.sheimi.sgit.activities.delegate.RepoOperationDelegate;
import me.sheimi.sgit.adapters.RepoOperationsAdapter;
import me.sheimi.sgit.database.models.Repo;
import me.sheimi.sgit.fragments.BaseFragment;
import me.sheimi.sgit.fragments.CommitsFragment;
import me.sheimi.sgit.fragments.FilesFragment;
import me.sheimi.sgit.fragments.StatusFragment;
import me.sheimi.sgit.repo.tasks.SheimiAsyncTask.AsyncTaskCallback;

import io.gitara.plugins.PluginEvents;

public class RepoDetailActivity extends SheimiFragmentActivity implements RepoDetailHost {

    private static final int FILES_FRAGMENT_INDEX = 0;
    private static final int COMMITS_FRAGMENT_INDEX = 1;
    private static final int STATUS_FRAGMENT_INDEX = 2;
    private static final int BRANCH_CHOOSE_ACTIVITY = 0;

    private static final String STATE_TAB = "gitara_selected_tab";
    private static final String TAG_FILES = "gitara_files";
    private static final String TAG_COMMITS = "gitara_commits";
    private static final String TAG_STATUS = "gitara_status";

    private FilesFragment mFilesFragment;
    private CommitsFragment mCommitsFragment;
    private StatusFragment mStatusFragment;

    private Repo mRepo;
    private RepoOperationDelegate mRepoDelegate;

    private final RepoDetailController mController = new RepoDetailController();
    private boolean mFragmentsReady = false;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case BRANCH_CHOOSE_ACTIVITY:
                String branchName = mRepo.getBranchName();
                if (branchName == null) {
                    showToastMessage(R.string.error_something_wrong);
                    return;
                }
                reset(branchName);
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepo = (Repo) getIntent().getSerializableExtra(Repo.TAG);
        // awful hack! workaround for null repo when returning from BranchChooser, but going to
        // shortly refactor passing in serialised repo, so not worth doing more to fix for now
        if (mRepo == null) {
            finish();
            return;
        }
        repoInit();
        setTitle(mRepo.getDiaplayName());
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        if (savedInstanceState != null) {
            mController.selectTab(savedInstanceState.getInt(STATE_TAB, FILES_FRAGMENT_INDEX));
        }
        setContentView(RepoDetailComposeEntry.create(this, mController, this, mRepo));
        applySystemBarIcons();
        PluginEvents.repoOpened(this, mRepo);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mController.getSelectedTab());
    }

    private void applySystemBarIcons() {
        WindowInsetsControllerCompat insets =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.setAppearanceLightStatusBars(true);
        insets.setAppearanceLightNavigationBars(true);
    }

    public RepoOperationDelegate getRepoDelegate() {
        if (mRepoDelegate == null) {
            mRepoDelegate = new RepoOperationDelegate(mRepo, this);
        }
        return mRepoDelegate;
    }

    @Override
    public void onNavigateUp() {
        finish();
    }

    @Override
    public void onChooseBranch() {
        Intent intent = new Intent(RepoDetailActivity.this, BranchChooserActivity.class);
        intent.putExtra(Repo.TAG, mRepo);
        startActivityForResult(intent, BRANCH_CHOOSE_ACTIVITY);
    }

    @Override
    public void onFragmentContainerReady() {
        if (mFragmentsReady || isFinishing() || isDestroyed()) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();

        FilesFragment files = (FilesFragment) fm.findFragmentByTag(TAG_FILES);
        if (files == null) {
            files = FilesFragment.newInstance(mRepo);
        }
        CommitsFragment commits = (CommitsFragment) fm.findFragmentByTag(TAG_COMMITS);
        if (commits == null) {
            commits = CommitsFragment.newInstance(mRepo, null);
        }
        StatusFragment status = (StatusFragment) fm.findFragmentByTag(TAG_STATUS);
        if (status == null) {
            status = StatusFragment.newInstance(mRepo);
        }
        mFilesFragment = files;
        mCommitsFragment = commits;
        mStatusFragment = status;

        int selected = mController.getSelectedTab();
        FragmentTransaction tx = fm.beginTransaction();
        if (!files.isAdded()) {
            tx.add(R.id.repo_fragment_container, files, TAG_FILES);
        }
        if (!commits.isAdded()) {
            tx.add(R.id.repo_fragment_container, commits, TAG_COMMITS);
        }
        if (!status.isAdded()) {
            tx.add(R.id.repo_fragment_container, status, TAG_STATUS);
        }
        BaseFragment[] all = {files, commits, status};
        for (int i = 0; i < all.length; i++) {
            if (i == selected) {
                tx.show(all[i]);
            } else {
                tx.hide(all[i]);
            }
        }
        tx.commitNowAllowingStateLoss();
        mFragmentsReady = true;
        if (selected == STATUS_FRAGMENT_INDEX) {
            mStatusFragment.reset();
        }
    }

    @Override
    public void onTabSelected(int index) {
        if (!mFragmentsReady) {
            return;
        }
        applyTabVisibility(index);
    }

    private void applyTabVisibility(int index) {
        if (!mFragmentsReady) {
            return;
        }
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        BaseFragment[] all = {mFilesFragment, mCommitsFragment, mStatusFragment};
        for (int i = 0; i < all.length; i++) {
            if (i == index) {
                tx.show(all[i]);
            } else {
                tx.hide(all[i]);
            }
        }
        tx.commitAllowingStateLoss();
        if (index == STATUS_FRAGMENT_INDEX) {
            mStatusFragment.reset();
        }
    }

    @Override
    public void onCommitSearch(String query) {
        if (mCommitsFragment == null || !mCommitsFragment.isAdded() || mCommitsFragment.getView() == null) {
            return;
        }
        mCommitsFragment.setFilter(query);
    }

    @Override
    public ListView createOperationsList(Context context) {
        ListView list = new ListView(context);
        RepoOperationsAdapter adapter = new RepoOperationsAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(adapter);
        return list;
    }

    private BaseFragment fragmentAt(int index) {
        switch (index) {
            case COMMITS_FRAGMENT_INDEX:
                return mCommitsFragment;
            case STATUS_FRAGMENT_INDEX:
                return mStatusFragment;
            default:
                return mFilesFragment;
        }
    }

    public void reset(String commitName) {
        reset();
    }

    public void reset() {
        if (mFilesFragment != null) {
            mFilesFragment.reset();
        }
        if (mCommitsFragment != null) {
            mCommitsFragment.reset();
        }
        if (mStatusFragment != null) {
            mStatusFragment.reset();
        }
        mController.refresh();
    }

    public void setFilesFragment(FilesFragment filesFragment) {
        mFilesFragment = filesFragment;
    }

    public FilesFragment getFilesFragment() {
        return mFilesFragment;
    }

    public void setCommitsFragment(CommitsFragment commitsFragment) {
        mCommitsFragment = commitsFragment;
    }

    public void setStatusFragment(StatusFragment statusFragment) {
        mStatusFragment = statusFragment;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DEL:
                if (keyCode == KeyEvent.KEYCODE_DEL && mController.getSearching()) {
                    return super.onKeyUp(keyCode, event);
                }
                if (mController.consumeBack()) {
                    return true;
                }
                BaseFragment current = fragmentAt(mController.getSelectedTab());
                if (current != null) {
                    OnBackClickListener onBackClickListener = current.getOnBackClickListener();
                    if (onBackClickListener != null && onBackClickListener.onClick()) {
                        return true;
                    }
                }
                finish();
                return true;
            case KeyEvent.KEYCODE_F:
                if (mController.getSearching()) {
                    return super.onKeyUp(keyCode, event);
                }
                mController.selectTab(FILES_FRAGMENT_INDEX);
                return true;
            case KeyEvent.KEYCODE_C:
                if (mController.getSearching()) {
                    return super.onKeyUp(keyCode, event);
                }
                mController.selectTab(COMMITS_FRAGMENT_INDEX);
                return true;
            case KeyEvent.KEYCODE_S:
                if (mController.getSearching()) {
                    return super.onKeyUp(keyCode, event);
                }
                mController.selectTab(STATUS_FRAGMENT_INDEX);
                return true;
            case KeyEvent.KEYCODE_SLASH:
                if (mController.getSearching()) {
                    return super.onKeyUp(keyCode, event);
                }
                if (event.isShiftPressed()) {
                    showKeyboardShortcutsHelpOverlay();
                }
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void showKeyboardShortcutsHelpOverlay() {
        showMessageDialog(R.string.dialog_keymap_title, getString(R.string.dialog_keymap_mesg));
    }

    public void error() {
        finish();
        showToastMessage(R.string.error_unknown);
    }

    public class ProgressCallback implements AsyncTaskCallback {

        private int mInitMsg;

        public ProgressCallback(int initMsg) {
            mInitMsg = initMsg;
        }

        @Override
        public void onPreExecute() {
            mController.showProgress(
                    getString(mInitMsg),
                    getString(R.string.progress_left_init),
                    getString(R.string.progress_right_init));
        }

        @Override
        public void onProgressUpdate(String... progress) {
            int value;
            try {
                value = Integer.parseInt(progress[3]);
            } catch (NumberFormatException e) {
                value = 0;
            }
            mController.updateProgress(progress[0], progress[1], progress[2], value);
        }

        @Override
        public void onPostExecute(Boolean isSuccess) {
            mController.hideProgress();
            reset();
            PluginEvents.operationFinished(
                    RepoDetailActivity.this, mRepo, mInitMsg, Boolean.TRUE.equals(isSuccess));
        }

        @Override
        public boolean doInBackground(Void... params) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
            return true;
        }

    }

    public void closeOperationDrawer() {
        mController.closeOperations();
    }

    public void enterDiffActionMode() {
        mController.selectTab(COMMITS_FRAGMENT_INDEX);
        applyTabVisibility(COMMITS_FRAGMENT_INDEX);
        if (mCommitsFragment != null) {
            mCommitsFragment.enterDiffActionMode();
        }
    }

    private void repoInit() {
        mRepo.updateLatestCommitInfo();
        mRepo.getRemotes();
    }

}