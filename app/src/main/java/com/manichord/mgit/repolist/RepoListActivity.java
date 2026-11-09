package com.manichord.mgit.repolist;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.manichord.mgit.transport.MGitHttpConnectionFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import io.gitara.ui.repolist.CloneRequest;
import io.gitara.ui.repolist.RepoListComposeEntry;
import io.gitara.ui.repolist.RepoListController;
import io.gitara.ui.repolist.RepoListHost;
import me.sheimi.android.activities.SheimiFragmentActivity;
import me.sheimi.sgit.MGitApplication;
import me.sheimi.sgit.R;
import me.sheimi.sgit.activities.RepoDetailActivity;
import me.sheimi.sgit.activities.UserSettingsActivity;
import me.sheimi.sgit.activities.explorer.ExploreFileActivity;
import me.sheimi.sgit.activities.explorer.ImportRepositoryActivity;
import me.sheimi.sgit.database.RepoDbManager;
import me.sheimi.sgit.database.models.Repo;
import me.sheimi.sgit.dialogs.DummyDialogListener;
import me.sheimi.sgit.dialogs.ImportLocalRepoDialog;
import me.sheimi.sgit.repo.tasks.repo.CloneTask;
import me.sheimi.sgit.ssh.PrivateKeyUtils;
import timber.log.Timber;

public class RepoListActivity extends SheimiFragmentActivity implements RepoListHost {

    private static final int REQUEST_IMPORT_REPO = 0;

    private final RepoListController controller = new RepoListController();

    public enum ClickActions {
        CLONE, CANCEL
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(RepoListComposeEntry.create(this, controller, this));
        applySystemBarIcons();

        PrivateKeyUtils.migratePrivateKeys();
        initUpdatedSSL();
        handleViewIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // check everytime on the repo list activity that we still have file access permission
        checkAndRequestRequiredPermissions(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            controller.refresh();
        }
    }

    private void applySystemBarIcons() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insets =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.setAppearanceLightStatusBars(!dark);
        insets.setAppearanceLightNavigationBars(!dark);
    }

    private void handleViewIntent() {
        Uri uri = this.getIntent().getData();
        if (uri == null) {
            return;
        }
        Context context = getApplicationContext();
        URL remoteRepoUrl = null;
        try {
            remoteRepoUrl = new URL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
        } catch (MalformedURLException e) {
            Toast.makeText(context, R.string.invalid_url, Toast.LENGTH_LONG).show();
            Timber.e(e);
        }
        if (remoteRepoUrl == null) {
            return;
        }

        String remoteUrl = remoteRepoUrl.toString();
        String repoName = remoteUrl.substring(remoteUrl.lastIndexOf("/") + 1);
        StringBuilder repoUrlBuilder = new StringBuilder(remoteUrl);

        // need git extension to clone some repos
        if (!remoteUrl.toLowerCase().endsWith(getString(R.string.git_extension))) {
            repoUrlBuilder.append(getString(R.string.git_extension));
        } else { // if has git extension remove it from repository name
            repoName = repoName.substring(0, repoName.lastIndexOf('.'));
        }

        // Check if there are others repositories with same remote
        List<Repo> repositoriesWithSameRemote = Repo.getRepoList(context, RepoDbManager.searchRepo(remoteUrl));

        if (repositoriesWithSameRemote.size() > 0) {
            Toast.makeText(context, R.string.repository_already_present, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(context, RepoDetailActivity.class);
            intent.putExtra(Repo.TAG, repositoriesWithSameRemote.get(0));
            startActivity(intent);
        } else if (Repo.getDir(((MGitApplication) getApplicationContext()).getPrefenceHelper(), repoName).exists()) {
            // Repository with name already exists, see https://github.com/hastagaming/Gitara/issues/289
            controller.openClone(repoUrlBuilder.toString());
        } else {
            final String cloningStatus = getString(R.string.cloning);
            Repo repo = Repo.createRepo(repoName, repoUrlBuilder.toString(), cloningStatus);
            CloneTask task = new CloneTask(repo, true, cloningStatus, null);
            task.executeTask();
        }
    }

    @Override
    public void onOpenRepo(Repo repo) {
        Intent intent = new Intent(this, RepoDetailActivity.class);
        intent.putExtra(Repo.TAG, repo);
        startActivity(intent);
        forwardTransition();
    }

    @Override
    public void onImportRepo() {
        Intent intent = new Intent(this, ImportRepositoryActivity.class);
        startActivityForResult(intent, REQUEST_IMPORT_REPO);
        forwardTransition();
    }

    @Override
    public void onOpenSettings() {
        startActivity(new Intent(this, UserSettingsActivity.class));
    }

    @Override
    public String onCloneRequested(CloneRequest request) {
        String url = request.getRemoteUrl();
        String name = request.getFolderName();
        if (url.isEmpty()) {
            return "Remote URL is required.";
        }
        if (name.isEmpty()) {
            return "Folder name is required.";
        }
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            return "Folder name is not valid.";
        }
        File target = Repo.getDir(((MGitApplication) getApplicationContext()).getPrefenceHelper(), name);
        if (target.exists()) {
            return "A folder named \"" + name + "\" already exists.";
        }

        final String cloningStatus = getString(R.string.cloning);
        Repo repo = Repo.createRepo(name, url, cloningStatus);
        if (!request.getUsername().isEmpty()) {
            repo.setUsername(request.getUsername());
            repo.setPassword(request.getPassword());
            repo.saveCredentials();
        }
        CloneTask task = new CloneTask(repo, request.getRecursive(), cloningStatus, null);
        task.executeTask();
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK)
            return;
        switch (requestCode) {
            case REQUEST_IMPORT_REPO:
                final String path = data.getExtras().getString(
                        ExploreFileActivity.RESULT_PATH);
                File file = new File(path);
                File dotGit = new File(file, Repo.DOT_GIT_DIR);
                if (!dotGit.exists()) {
                    showToastMessage(getString(R.string.error_no_repository));
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(
                        this);
                builder.setTitle(R.string.dialog_comfirm_import_repo_title);
                builder.setMessage(R.string.dialog_comfirm_import_repo_msg);
                builder.setNegativeButton(R.string.label_cancel,
                        new DummyDialogListener());
                builder.setPositiveButton(R.string.label_import,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialogInterface, int i) {
                                Bundle args = new Bundle();
                                args.putString(ImportLocalRepoDialog.FROM_PATH, path);
                                ImportLocalRepoDialog rld = new ImportLocalRepoDialog();
                                rld.setArguments(args);
                                rld.show(getSupportFragmentManager(), "import-local-dialog");
                            }
                        });
                builder.show();
                break;
        }
    }

    public void finish() {
        rawfinish();
    }

    private void initUpdatedSSL() {
        MGitHttpConnectionFactory.install();
        Timber.i("Installed custom HTTPS factory");
    }
}