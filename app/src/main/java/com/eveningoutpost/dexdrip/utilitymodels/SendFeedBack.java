package com.eveningoutpost.dexdrip.utilitymodels;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import com.eveningoutpost.dexdrip.BaseAppCompatActivity;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.R;
import java.net.URLEncoder;

import androidx.appcompat.app.AlertDialog;

public class SendFeedBack extends BaseAppCompatActivity {

    private static final String TAG = "jamorham feedback";
    private static final String FEEDBACK_CONTACT_REFERENCE = "feedback-contact-reference";

    private String type_of_message = "Unknown";

    private String log_data = "";

    RatingBar myrating;
    TextView ratingtext;
    EditText contact;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_feed_back);

        myrating = (RatingBar) findViewById(R.id.ratingBar);
        ratingtext = (TextView) findViewById(R.id.ratingtext);
        contact = (EditText) findViewById(R.id.contactText);
        contact.setText(PersistentStore.getString(FEEDBACK_CONTACT_REFERENCE));

        Intent intent = getIntent();
        if (intent != null) {
            final Bundle bundle = intent.getExtras();
            if (bundle != null) {
                // TODO this probably should just use generic text method
                final String str = bundle.getString("request_translation");
                if (str != null) {
                    // don't extract string - english only
                    ((EditText) findViewById(R.id.yourText)).setText("Dear developers, please may I request that you add translation capability for: " + str + "\n\n");
                    type_of_message = "Language request";

                }
                final String str2 = bundle.getString("generic_text");
                if (str2 != null) {
                    log_data = str2;
                    ((EditText) findViewById(R.id.yourText)).setText(log_data.length() > 300 ? "\n\nPlease describe what you think these logs may show. Explain the problem if there is one.\n\nAttached " + log_data.length() + " characters of log data. (hidden)\n\n" : log_data);
                    type_of_message = "Log Push";
                    myrating.setVisibility(View.GONE);
                    ratingtext.setVisibility(View.GONE);
                }
            }
        }
        if (type_of_message.equals("Unknown")) {
            askType();
        }

    }

    public void closeActivity(View myview) {
        finish();
    }

    private void askType() {
        final CharSequence[] items = {"Bug Report", "Compliment", "Question", "Other"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Type of feedback?");
        builder.setSingleChoiceItems(items, -1,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int item) {
                        type_of_message = items[item].toString();
                        dialog.dismiss();
                    }
                });
        final AlertDialog typeDialog = builder.create();
        typeDialog.show();
    }

    private void askEmailAddress() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Please supply email address or other contact reference");


        final EditText input = new EditText(this);

        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        builder.setView(input);


        builder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                contact.setText(input.getText().toString());
                sendFeedback(null);
            }
        });
        builder.setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        AlertDialog dialog = builder.create();
        try {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        } catch (NullPointerException e) {
            //
        }
        dialog.show();
    }

    public void sendFeedback(View myview) {

        final EditText contact = (EditText) findViewById(R.id.contactText);
        final EditText yourtext = (EditText) findViewById(R.id.yourText);

        if (yourtext.length() == 0) {
            toast("No text entered - cannot send blank");
            return;
        }

        if (contact.length() == 0) {
            toast("Without some contact info we cannot reply");
            askEmailAddress();
            return;
        }

        if (type_of_message.equals("Unknown")) {
            askType();
            return;
        }

        PersistentStore.setString(FEEDBACK_CONTACT_REFERENCE, contact.getText().toString());

        // M2: warn user that feedback will be a public GitHub issue containing medical data
        final String contactText = contact.getText().toString();
        final String messageText = yourtext.getText().toString();
        new AlertDialog.Builder(this)
            .setTitle("Public issue on GitHub")
            .setMessage("Your feedback will be posted as a public GitHub issue.\n\n"
                    + "If your message contains medical data (glucose values, insulin doses), "
                    + "it will be publicly visible.\n\nContinue?")
            .setPositiveButton("Continue", (dialog, which) -> doSendFeedback(contactText, messageText))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void doSendFeedback(final String contactText, final String messageText) {
        try {
            String title = "[" + type_of_message + "] xDrip+ CamAPS Bridge Feedback";
            String body = "**Contact:** " + contactText + "\n\n"
                    + "**Message:**\n" + messageText + "\n\n"
                    + "**Rating:** " + myrating.getRating() + "/5\n\n"
                    + "---\n"
                    + "**Type:** " + type_of_message + "\n\n";
            if (log_data != null && !log_data.isEmpty()) {
                // Truncate log data — already long in the body
                String truncatedLog = log_data.length() > 1000
                        ? log_data.substring(0, 1000) + "\n... (truncated)" : log_data;
                body += "**Log data:**\n```\n" + truncatedLog + "\n```\n";
            }

            // M1: limit body to ~2000 chars before URL encoding
            // URLEncoder can triple the size of special chars — target safe URL length
            if (body.length() > 2000) {
                body = body.substring(0, 2000) + "\n\n... (truncated)";
            }

            String url = "https://github.com/Najah69/xDrip-plus/issues/new"
                    + "?title=" + URLEncoder.encode(title, "UTF-8")
                    + "&body="  + URLEncoder.encode(body, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
            log_data = "";
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error opening feedback URL: " + e);
            JoH.static_toast_short("Error opening browser");
        }
    }

    private void toast(final String msg) {
        try {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                }
            });
            Log.d(TAG, "Toast msg: " + msg);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't display toast: " + msg);
        }
    }
}
