"""The xg.glass Smart Glasses integration."""
from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.device_registry import DeviceInfo

from .const import (
    ATTR_MESSAGE,
    ATTR_TITLE,
    CONF_DEVICE_NAME,
    DOMAIN,
    EVENT_DISPLAY,
    SERVICE_DISPLAY,
)

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [Platform.NOTIFY]

_DISPLAY_SERVICE_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_MESSAGE): cv.string,
        vol.Optional(ATTR_TITLE): cv.string,
    }
)


def device_info(entry: ConfigEntry) -> DeviceInfo:
    """Return device info for a glasses config entry."""
    return DeviceInfo(
        identifiers={(DOMAIN, entry.entry_id)},
        name=entry.data[CONF_DEVICE_NAME],
        model="Rokid Glasses",
        manufacturer="xg.glass",
        configuration_url="https://github.com/Teagan42/xg-glass-sdk",
    )


async def async_setup(hass: HomeAssistant, config: dict[str, Any]) -> bool:
    """Set up the xg.glass integration (YAML entry point – no-op)."""
    hass.data.setdefault(DOMAIN, {})
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up xg.glass from a config entry."""
    hass.data.setdefault(DOMAIN, {})
    hass.data[DOMAIN][entry.entry_id] = {}

    # Register a convenience service so automations can push text without
    # needing to know the exact notify entity_id.
    async def _handle_display(call: ServiceCall) -> None:
        hass.bus.async_fire(
            EVENT_DISPLAY,
            {
                "entry_id": entry.entry_id,
                "message": call.data[ATTR_MESSAGE],
                "title": call.data.get(ATTR_TITLE),
            },
        )

    # Only register the service for the first (or only) entry to avoid
    # duplicate service registration errors on multi-device setups.
    if not hass.services.has_service(DOMAIN, SERVICE_DISPLAY):
        hass.services.async_register(
            DOMAIN,
            SERVICE_DISPLAY,
            _handle_display,
            schema=_DISPLAY_SERVICE_SCHEMA,
        )

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    _LOGGER.debug("xg.glass entry %s set up (%s)", entry.entry_id, entry.data[CONF_DEVICE_NAME])
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        hass.data[DOMAIN].pop(entry.entry_id, None)
        # Remove the shared service only when no other entries remain.
        if not hass.data[DOMAIN]:
            hass.services.async_remove(DOMAIN, SERVICE_DISPLAY)
    return unload_ok
