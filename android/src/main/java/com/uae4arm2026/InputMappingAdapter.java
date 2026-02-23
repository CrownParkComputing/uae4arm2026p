package com.uae4arm2026;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.Map;

/**
 * Adapter for displaying input mappings in a two-column ListView.
 * 
 * Left column: Game Control (Up, Down, Left, Right, Fire)
 * Right column: Device Control (tap to change)
 */
public class InputMappingAdapter extends BaseAdapter {

    private final Context context;
    private final int[] gameControls;
    private final Map<Integer, Integer> mappings;

    public InputMappingAdapter(Context context, int[] gameControls, Map<Integer, Integer> mappings) {
        this.context = context;
        this.gameControls = gameControls;
        this.mappings = mappings;
    }

    @Override
    public int getCount() {
        return gameControls.length;
    }

    @Override
    public Object getItem(int position) {
        return gameControls[position];
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.list_item_mapping, parent, false);
            
            holder = new ViewHolder();
            holder.gameControlText = convertView.findViewById(R.id.game_control_text);
            holder.deviceControlText = convertView.findViewById(R.id.device_control_text);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        int gameControl = gameControls[position];
        
        // Set game control name (left column)
        holder.gameControlText.setText(InputMappingActivity.getGameControlName(gameControl));
        
        // Set device control name (right column)
        Integer deviceButton = mappings.get(gameControl);
        if (deviceButton != null) {
            holder.deviceControlText.setText(InputMappingActivity.getDeviceButtonName(deviceButton));
            holder.deviceControlText.setTextColor(0xFF4CAF50); // Green
        } else {
            holder.deviceControlText.setText("Not Set");
            holder.deviceControlText.setTextColor(0xFFFF9800); // Orange
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView gameControlText;
        TextView deviceControlText;
    }
}